package com.itheima.consultant.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String CHAT_EXCHANGE = "chat.exchange.v2";
    public static final String CHAT_QUEUE = "chat.queue.v2";
    public static final String CHAT_ROUTING_KEY = "chat.request";
    public static final String CHAT_DLQ = "chat.dlq.v2";
    public static final String CHAT_DLX = "chat.dlx.v2";

    public static final String RESPONSE_EXCHANGE = "chat.response.exchange.v2";
    public static final String RESPONSE_QUEUE = "chat.response.queue.v2";
    public static final String RESPONSE_ROUTING_KEY = "chat.response";

    @Value("${rabbitmq.chat.concurrency:3}")
    private int concurrency;

    @Value("${rabbitmq.chat.max-concurrency:10}")
    private int maxConcurrency;

    @Value("${rabbitmq.chat.prefetch:1}")
    private int prefetch;

    @Value("${rabbitmq.chat.queue-max-length:10000}")
    private long queueMaxLength;

    @Bean
    public DirectExchange chatExchange() {
        return ExchangeBuilder
                .directExchange(CHAT_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(CHAT_DLX)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange responseExchange() {
        return ExchangeBuilder
                .directExchange(RESPONSE_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue chatQueue() {
        return QueueBuilder
                .durable(CHAT_QUEUE)
                .withArgument("x-dead-letter-exchange", CHAT_DLX)
                .withArgument("x-dead-letter-routing-key", "chat.dead")
                .withArgument("x-max-length", queueMaxLength)
                .withArgument("x-overflow", "reject-publish")
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
                .durable(CHAT_DLQ)
                .build();
    }

    @Bean
    public Queue responseQueue() {
        return QueueBuilder
                .durable(RESPONSE_QUEUE)
                .build();
    }

    @Bean
    public Binding chatBinding() {
        return BindingBuilder
                .bind(chatQueue())
                .to(chatExchange())
                .with(CHAT_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("chat.dead");
    }

    @Bean
    public Binding responseBinding() {
        return BindingBuilder
                .bind(responseQueue())
                .to(responseExchange())
                .with(RESPONSE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(maxConcurrency);
        factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
