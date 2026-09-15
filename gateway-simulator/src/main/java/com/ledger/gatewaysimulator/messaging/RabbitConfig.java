package com.ledger.gatewaysimulator.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
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

    // Gateway Simulator's own exchange for events it publishes (deposit.credited,
    // withdrawal.reversed, etc.) — this service owns and declares this one, unlike the
    // ledger.events exchange it only binds to as a consumer (see below for the
    // ledger.transaction.posted consumer wiring).
    @Bean
    public TopicExchange gatewaySimExchange() {
        return new TopicExchange(MessagingConstants.GATEWAY_SIM_EXCHANGE, true, false);
    }

    // Binds this service's own queue to V1's existing exchange to consume
    // ledger.transaction.posted — does NOT redeclare the exchange itself as a new resource
    // this service owns; it's V1's exchange, declared idempotently the same way any consumer
    // safely can (declaring an already-existing exchange with matching properties is a no-op).
    // Matches Holds Service's own RabbitConfig.ledgerExchange() bean exactly.
    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(MessagingConstants.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    public Queue gatewaySimTransactionPostedQueue() {
        return new Queue(MessagingConstants.GATEWAY_SIM_TRANSACTION_POSTED_QUEUE, true);
    }

    @Bean
    public Binding gatewaySimTransactionPostedBinding(Queue gatewaySimTransactionPostedQueue,
                                                        @Qualifier("ledgerExchange") TopicExchange ledgerExchange) {
        return BindingBuilder.bind(gatewaySimTransactionPostedQueue)
                .to(ledgerExchange)
                .with(MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY);
    }
}
