package com.silver.backend.bootstrap;

import com.silver.backend.business.auth.AuthService;
import com.silver.backend.infrastructure.MailGateway;
import com.silver.backend.infrastructure.TaskQueue;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            TaskQueue queue, MailGateway mail, AuthService auth) {
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
        };
    }
}
