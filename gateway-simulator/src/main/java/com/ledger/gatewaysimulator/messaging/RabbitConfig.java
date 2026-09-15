package com.ledger.gatewaysimulator.messaging;

import org.springframework.amqp.core.TopicExchange;
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

    // Gateway Simulator's own exchange for events it publishes (deposit.credited,
    // withdrawal.reversed, etc.) — this service owns and declares this one, unlike the
    // ledger.events exchange it only binds to as a consumer (see Task 8's RabbitConfig
    // additions for the ledger.transaction.posted consumer wiring).
    @Bean
    public TopicExchange gatewaySimExchange() {
        return new TopicExchange(MessagingConstants.GATEWAY_SIM_EXCHANGE, true, false);
    }
}
