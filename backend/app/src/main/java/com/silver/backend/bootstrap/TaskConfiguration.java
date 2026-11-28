package com.silver.backend.bootstrap;

import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.media.MediaService;
import com.silver.backend.business.work.WorkService;
import com.silver.backend.infrastructure.MailGateway;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.function.Consumer;

@Configuration
public class TaskConfiguration {
    private TaskQueue.Handler handler(String kind, Consumer<Map<String, Object>> action) {
        return new TaskQueue.Handler() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public void execute(Map<String, Object> payload) {
                action.accept(payload);
            }
        };
    }

    @Bean
    SmartInitializingSingleton registerTasks(
            TaskQueue queue, MailGateway mail, AuthService auth, WorkService work, MediaService media) {
        return () -> {
          queue.register(
            handler(
                    "MAIL",
                    payload -> {
                        if (auth.mailStillRelevant(payload)) {
                            mail.send(
                                    (String) payload.get("email"),
                                    (String) payload.get("subject"),
                                    (String) payload.get("body"));
                        }
                    }
            )
          );
          queue.register(handler("PUBLISH", work::finishPublish));
          queue.register(handler("ASSET", media::process));
        };
    }

    public static class ScheduledJobs {
        private final TaskQueue queue;

        ScheduledJobs(TaskQueue queue) {
            this.queue = queue;
        }

        @Scheduled(fixedDelayString = "${htw.task-poll-ms:1000}")
        public void tasks() {
            queue.tick();
        }
    }

    @Bean
    @ConditionalOnProperty(name = "htw.jobs-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledJobs scheduledJobs(
            TaskQueue queue
    ) {
        return new ScheduledJobs(queue);
    }
}
