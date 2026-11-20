package com.silver.backend;

import com.silver.backend.infrastructure.MailGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailGatewayTest {
    @Test
    void passesMailContent() {
        MailSender sender = mock(MailSender.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", sender);
        MailGateway gateway = gateway(beans);

        gateway.send("reader@example.test", "验证邮箱", "验证码 000123");

        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("sender@example.test", message.getValue().getFrom());
        assertArrayEquals(new String[] {"reader@example.test"}, message.getValue().getTo());
        assertEquals("验证邮箱", message.getValue().getSubject());
        assertEquals("验证码 000123", message.getValue().getText());
    }

    @Test
    void rejectsMissingSender() {
        MailGateway gateway = gateway(new StaticListableBeanFactory());
        assertThrows(IllegalStateException.class, () -> gateway.send("reader@example.test", "标题", "正文"));
    }

    private MailGateway gateway(StaticListableBeanFactory beans) {
        MailGateway gateway = new MailGateway();
        ReflectionTestUtils.setField(gateway, "from", "sender@example.test");
        ReflectionTestUtils.setField(gateway, "sender", beans.getBeanProvider(MailSender.class));
        return gateway;
    }
}
