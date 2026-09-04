package com.ledger.holdsservice.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    // Binds this service's own queue to V1's existing exchange to consume
    // ledger.transaction.posted — does NOT redeclare the exchange itself as a new resource
    // this service owns; it's V1's exchange, declared idempotently the same way any consumer
    // safely can (declaring an already-existing exchange with matching properties is a no-op).
    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(MessagingConstants.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    public Queue holdsTransactionPostedQueue() {
        return new Queue(MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, true);
    }

    @Bean
    public Binding holdsTransactionPostedBinding(Queue holdsTransactionPostedQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(holdsTransactionPostedQueue)
                .to(ledgerExchange)
                .with(MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY);
    }
}
