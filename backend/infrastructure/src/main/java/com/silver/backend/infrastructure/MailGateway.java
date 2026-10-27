package com.silver.backend.infrastructure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

@Component
public class MailGateway {
    @Autowired
    private ObjectProvider<MailSender> sender;
    @Value("${htw.mail-from}")
    private String from;

    public void send(String email, String subject, String body) {
        MailSender mailSender = sender.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("SMTP is not configured");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
