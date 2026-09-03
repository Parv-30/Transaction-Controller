package com.ledger.txprocessor.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitPublisherConfig {

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;
    @Value("${spring.rabbitmq.port:5672}")
    private int port;
    @Value("${spring.rabbitmq.username:guest}")
    private String username;
    @Value("${spring.rabbitmq.password:guest}")
    private String password;
    @Value("${spring.rabbitmq.requested-heartbeat:10}")
    private int requestedHeartbeatSeconds;

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        // A short heartbeat keeps the AMQP connection alive against idle-connection drops from
        // intermediate proxies (notably the local Docker Desktop TCP proxy used in dev/test
        // environments), which otherwise silently close an idle socket and surface as an
        // unrecoverable EOFException deep in the AMQP client on the next read.
        factory.getRabbitConnectionFactory().setRequestedHeartbeat(requestedHeartbeatSeconds);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setMandatory(true);
        return template;
    }

    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(MessagingConstants.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    public Queue transactionPostedQueue() {
        return new Queue(MessagingConstants.TRANSACTION_POSTED_QUEUE, true);
    }

    @Bean
    public Binding transactionPostedBinding(Queue transactionPostedQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(transactionPostedQueue)
                .to(ledgerExchange)
                .with(MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY);
    }
}
