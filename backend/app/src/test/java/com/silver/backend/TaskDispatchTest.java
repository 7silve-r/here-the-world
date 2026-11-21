package com.silver.backend;

import com.silver.backend.bootstrap.TaskConfiguration;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.dto.EmailInput;
import com.silver.backend.business.dto.RegisterInput;
import com.silver.backend.business.dto.VerifyEmailInput;
import com.silver.backend.business.persistence.UserRow;
import com.silver.backend.business.persistence.mapper.AuthMapper;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.MailGateway;
import com.silver.backend.infrastructure.TaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskDispatchTest extends BackendTestSupport {
    @Autowired private AuthService auth;
    @Autowired private AuthMapper users;
    @Autowired private TaskQueue queue;
    @Autowired private TaskQueue.TaskMapper tasks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Crypto crypto;
    @Autowired private JsonCodec json;
    @Autowired private ApplicationContext application;
    @MockitoBean private MailGateway mail;

    private final Set<String> fixtureUsers = new HashSet<>();

    @BeforeEach
    void clearTasks() {
        jdbc.update("DELETE FROM background_tasks");
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM background_tasks");
        for (String user : fixtureUsers) {
            jdbc.update("DELETE FROM verification_codes WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void sendsMail() {
        assertTrue(application.getBeansOfType(TaskConfiguration.ScheduledJobs.class).isEmpty());
        UserRow user = register();
        TaskQueue.Task task = onlyTask();
        Map<String, Object> payload = payload(task);
        verifyNoInteractions(mail);

        queue.tick();
        queue.tick();

        verify(mail).send(user.getEmail(), (String) payload.get("subject"), (String) payload.get("body"));
        verifyNoMoreInteractions(mail);
        assertDone(task.id);
        assertEquals(0, tasks.get(task.id).attempts);
        assertNotNull(users.code(user.getId()));
        assertFalse(users.user(user.getId()).isEmailVerified());
    }

    @Test
    void skipsExpired() {
        UserRow user = register();
        TaskQueue.Task task = onlyTask();
        jdbc.update("UPDATE verification_codes SET expires_at = ? WHERE user_id = ?",
                Values.now() - 1_000, user.getId());

        queue.tick();

        verifyNoInteractions(mail);
        assertDone(task.id);
    }

    @Test
    void skipsOldCode() {
        UserRow user = register();
        TaskQueue.Task old = onlyTask();
        jdbc.update("UPDATE verification_codes SET sent_at = ? WHERE user_id = ?",
                Values.now() - 61_000, user.getId());
        auth.resend(new EmailInput(user.getEmail()));
        String nextId = jdbc.queryForObject(
                "SELECT id FROM background_tasks WHERE id <> ?", String.class, old.id);
        TaskQueue.Task next = tasks.get(nextId);
        Map<String, Object> current = payload(next);

        queue.tick();
        queue.tick();

        verify(mail).send(user.getEmail(), (String) current.get("subject"), (String) current.get("body"));
        verifyNoMoreInteractions(mail);
        assertDone(old.id);
        assertDone(next.id);
    }

    @Test
    void skipsVerified() {
        UserRow user = register();
        TaskQueue.Task task = onlyTask();
        var matcher = Pattern.compile("[0-9]{6}").matcher((String) payload(task).get("body"));
        assertTrue(matcher.find());
        auth.verify(new VerifyEmailInput(user.getEmail(), matcher.group()));

        queue.tick();

        verifyNoInteractions(mail);
        assertDone(task.id);
    }

    @Test
    void skipsMissing() {
        UserRow user = register();
        TaskQueue.Task task = onlyTask();
        users.deleteCode(user.getId());

        queue.tick();

        verifyNoInteractions(mail);
        assertDone(task.id);
    }

    @Test
    void retriesMail() {
        UserRow user = register();
        TaskQueue.Task task = onlyTask();
        Map<String, Object> payload = payload(task);
        String subject = (String) payload.get("subject");
        String body = (String) payload.get("body");
        doThrow(new MailSendException("SMTP test failure"))
                .doNothing().when(mail).send(user.getEmail(), subject, body);
        long before = Values.now();

        queue.tick();

        TaskQueue.Task pending = tasks.get(task.id);
        assertEquals("PENDING", pending.state);
        assertEquals(1, pending.attempts);
        assertEquals("MailSendException", pending.lastError);
        assertTrue(pending.availableAt >= before + 2_000);
        assertNull(pending.finishedAt);
        assertNull(pending.leaseToken);
        assertEquals(task.payload, pending.payload);

        // 调整数据库时间检查重试，不依靠 sleep 等待退避间隔。
        jdbc.update("UPDATE background_tasks SET available_at = ? WHERE id = ?",
                Values.now() + 60_000, task.id);
        queue.tick();
        verify(mail).send(user.getEmail(), subject, body);
        jdbc.update("UPDATE background_tasks SET available_at = ? WHERE id = ?",
                Values.now() - 1, task.id);
        queue.tick();

        verify(mail, times(2)).send(user.getEmail(), subject, body);
        verifyNoMoreInteractions(mail);
        assertDone(task.id);
        assertEquals(1, tasks.get(task.id).attempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "missing"})
    void pollingSwitch(String setting) {
        TaskQueue poller = mock(TaskQueue.class);
        MailGateway gateway = mock(MailGateway.class);
        AuthService authentication = mock(AuthService.class);
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(TaskConfiguration.class, Scheduling.class)
                .withPropertyValues("htw.task-poll-ms=3600000")
                .withInitializer(context -> {
                    // 预注册替身，只验证调度开关；邮件流程使用上面的真实数据库和队列。
                    context.getBeanFactory().registerSingleton("queue", poller);
                    context.getBeanFactory().registerSingleton("mail", gateway);
                    context.getBeanFactory().registerSingleton("auth", authentication);
                });
        if (!"missing".equals(setting)) {
            runner = runner.withPropertyValues("htw.jobs-enabled=" + setting);
        }

        runner.run(context -> {
            assertNull(context.getStartupFailure());
            if ("false".equals(setting)) {
                assertTrue(context.getBeansOfType(TaskConfiguration.ScheduledJobs.class).isEmpty());
                verify(poller, never()).tick();
            } else {
                assertEquals(1, context.getBeansOfType(TaskConfiguration.ScheduledJobs.class).size());
                verify(poller, timeout(5_000).atLeastOnce()).tick();
            }
        });
    }

    private UserRow register() {
        String suffix = Values.id().replace("-", "").substring(0, 12);
        String email = "dispatch-" + suffix + "@example.test";
        auth.register(new RegisterInput(email, "Test-password-123", "mail_" + suffix, "测试用户"));
        UserRow user = users.email(email);
        assertNotNull(user);
        fixtureUsers.add(user.getId());
        return user;
    }

    private TaskQueue.Task onlyTask() {
        List<String> ids = jdbc.queryForList("SELECT id FROM background_tasks", String.class);
        assertEquals(1, ids.size());
        return tasks.get(ids.get(0));
    }

    private Map<String, Object> payload(TaskQueue.Task task) {
        return json.readMap(crypto.decrypt(task.payload));
    }

    private void assertDone(String id) {
        TaskQueue.Task task = tasks.get(id);
        assertEquals("DONE", task.state);
        assertEquals("", task.payload);
        assertNotNull(task.finishedAt);
        assertNull(task.leaseToken);
        assertNull(task.lastError);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}
