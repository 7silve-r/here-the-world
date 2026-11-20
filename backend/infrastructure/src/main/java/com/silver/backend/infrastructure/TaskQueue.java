package com.silver.backend.infrastructure;

import com.silver.backend.common.Values;
import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskQueue {
    public static class Task {
        public String id, kind, payload, state, leaseToken, lastError;
        public long availableAt, leaseUntil, createdAt;
        public Long finishedAt;
        public int attempts;
    }

    @Mapper
    public interface TaskMapper {
        @Insert("""
            INSERT INTO background_tasks(id,kind,payload,state,attempts,available_at,lease_until,created_at)
            VALUES(#{id},#{kind},#{payload},'PENDING',0,#{availableAt},0,#{createdAt})
            """)
        public void insert(Task task);

        @Select("""
                SELECT lease_token 
                FROM background_tasks 
                WHERE id=#{id} AND state='RUNNING' 
                FOR UPDATE
                """)
        String lockedOwner(String id);

        @Select("""
                SELECT * 
                FROM background_tasks 
                WHERE (state='PENDING' 
                           AND available_at<=#{now}) 
                   OR(state='RUNNING' 
                          AND lease_until<=#{now}) 
                ORDER BY created_at,id LIMIT 1
                """)
        Task next(long now);

        @Update("""
                UPDATE background_tasks 
                SET state='RUNNING',lease_token=#{token},lease_until=#{until},finished_at=NULL 
                WHERE id=#{id} 
                  AND ((state='PENDING' 
                            AND available_at<=#{now}) 
                           OR (state='RUNNING' 
                                   AND lease_until<=#{now}))
                """)
        int claim(
                @Param("id") String id,
                @Param("token") String token,
                @Param("until") long until,
                @Param("now") long now);

        @Select("SELECT * FROM background_tasks WHERE id=#{id}")
        Task get(String id);

        @Update("""
                UPDATE background_tasks
                SET
                    state        = #{state},
                    attempts     = attempts + #{increment},
                    available_at = #{at},
                    lease_until  = 0,
                    lease_token  = NULL,
                    last_error   = #{error},
                
                    finished_at = CASE
                        WHEN #{state} IN ('DONE', 'FAILED')
                        THEN #{finished}
                        ELSE NULL
                    END,
                
                    payload = CASE
                        WHEN #{state} = 'DONE'
                        THEN ''
                        ELSE payload
                    END
                
                WHERE id = #{id}
                  AND lease_token = #{token}
                """)
        int finish(
                @Param("id") String id,
                @Param("token") String token,
                @Param("state") String state,
                @Param("at") long at,
                @Param("error") String error,
                @Param("increment") int increment,
                @Param("finished") long finished);

        @Select("""
                SELECT id 
                FROM background_tasks 
                WHERE (state='DONE'
                           AND finished_at<#{doneBefore}) 
                   OR(state='FAILED' 
                          AND finished_at<#{failedBefore}) 
                ORDER BY finished_at,id 
                LIMIT #{limit}
                """)
        List<String> cleanupCandidates(
                @Param("doneBefore") long doneBefore,
                @Param("failedBefore") long failedBefore,
                @Param("limit") int limit);

        @Delete("""
                DELETE FROM background_tasks 
                       WHERE id=#{id} 
                         AND ((state='DONE' 
                                   AND finished_at<#{doneBefore}) 
                                  OR (state='FAILED' 
                                          AND finished_at<#{failedBefore}))
                """)
        int deleteFinished(
                @Param("id") String id,
                @Param("doneBefore") long doneBefore,
                @Param("failedBefore") long failedBefore);
    }

    public interface Handler {
        String kind();
        void execute(Map<String, Object> payload);
    }

    public static class Pending extends RuntimeException {
        public Pending(){
            super("Waiting for dependency");
        }
    }

    @Autowired
    private TaskMapper mapper;
    @Autowired
    private JsonCodec json;
    @Autowired
    private Crypto crypto;
    private Map<String, Handler> handlers;
    private final long doneRetention;
    private final long failedRetention;
    private final int cleanupBatch;

    private record Execution(String taskId, String token){}

    private final ThreadLocal<Execution> execution = new ThreadLocal<>();

    public TaskQueue(@Value("${htw.cleanup.task-done-days}") long doneDays,
                     @Value("${htw.cleanup.task-failed-days}") long failedDays,
                     @Value("${htw.cleanup.batch-size}")  int cleanupBatch) {
        this.handlers = new ConcurrentHashMap<>();
        if (doneDays < 1 || failedDays < 1 || cleanupBatch < 1 || cleanupBatch > 1000) {
            throw new IllegalArgumentException(
                    "Cleanup retention must be positive; batch size must be 1..1000");
        }
        this.doneRetention = Math.multiplyExact(doneDays, 86_400_000L);
        this.failedRetention = Math.multiplyExact(failedDays, 86_400_000L);
        this.cleanupBatch = cleanupBatch;
    }

    public String enqueue(String kind, Map<String, Object> payload) {
        Task task = new Task();
        task.id = Values.id();
        task.kind = kind;
        task.payload = crypto.encrypt(json.write(payload));
        task.availableAt = Values.now();
        task.createdAt = Values.now();
        mapper.insert(task);
        return task.id;
    }

    public synchronized void register(Handler handler) {
        handlers.put(handler.kind(), handler);
    }

    public String executionId() {
        Execution current = execution.get();
        if (current == null) {
            throw new IllegalStateException("No task is executing on this thread");
        }
        return current.token();
    }

    public boolean ownsExecution() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Task ownership must be checked in the result transaction"
            );
        }
        Execution current = execution.get();
        return current != null &&
                Objects.equals(current.token(), mapper.lockedOwner(current.taskId()));
    }

    public void tick() {
        Task candidate = mapper.next(Values.now());
        if (candidate == null) {
            return;
        }
        String token = Values.id();
        if (mapper.claim(
                candidate.id,
                token,
                Values.now() + 600_000,
                Values.now()) != 1) {
            return;
        }
        Task task = mapper.get(candidate.id);
        if (task == null || !token.equals(task.leaseToken)) {
            return;
        }
        try{
            execution.set(new Execution(task.id, token));
            Handler handler = handlers.get(task.kind);
            if (handler == null) {
                throw new IllegalStateException("No handler for task " + task.kind);
            }
            handler.execute(json.readMap(crypto.decrypt(task.payload)));
            mapper.finish(task.id,
                    token,
                    "DONE",
                    Values.now(),
                    null,
                    0,
                    Values.now());
        }catch (Pending waiting) {
            mapper.finish(task.id,
                    token,
                    "PENDING",
                    Values.now() + 2_000,
                    null,
                    0,
                    Values.now());
        }catch (Exception failure) {
            int next = task.attempts + 1;
            mapper.finish(
                    task.id,
                    token,
                    next >= 8 ? "FAILED" : "PENDING",
                    Values.now() + Math.min(300_000L, 1_000L * (1L << Math.min(next, 8))),
                    failure.getClass().getSimpleName(),
                    1,
                    Values.now());
        }finally {
            execution.remove();
        }
    }

    public void cleanup() {
        long doneBefore = Values.now() - doneRetention;
        long failedBefore = Values.now() - failedRetention;
        for (String id : mapper.cleanupCandidates(doneBefore, failedBefore, cleanupBatch)) {
            mapper.deleteFinished(id, doneBefore, failedBefore);
        }
    }
}
