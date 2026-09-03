package com.ledger.txprocessor.cdc;

import com.ledger.txprocessor.domain.CdcProgress;
import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.messaging.OutboxEventPublisher;
import com.ledger.txprocessor.repository.CdcProgressRepository;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Component
public class OutboxChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {

    private static final Logger log = LoggerFactory.getLogger(OutboxChangeConsumer.class);

    private final OutboxEventPayloadMapper payloadMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final CdcProgressRepository cdcProgressRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TransactionTemplate transactionTemplate;

    public OutboxChangeConsumer(OutboxEventPayloadMapper payloadMapper,
                                 ProcessedEventRepository processedEventRepository,
                                 CdcProgressRepository cdcProgressRepository,
                                 OutboxEventPublisher outboxEventPublisher,
                                 TransactionTemplate transactionTemplate) {
        this.payloadMapper = payloadMapper;
        this.processedEventRepository = processedEventRepository;
        this.cdcProgressRepository = cdcProgressRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void handleBatch(java.util.List<ChangeEvent<String, String>> records,
                             DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer) throws InterruptedException {
        for (ChangeEvent<String, String> record : records) {
            if (record.value() != null) {
                processRecord(record.value());
            }
            committer.markProcessed(record);
        }
        committer.markBatchFinished();
    }

    private void processRecord(String valueJson) {
        var parsed = payloadMapper.parse(valueJson);
        if (parsed == null) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (processedEventRepository.findByOutboxEventId(parsed.id()).isPresent()) {
                log.debug("Outbox event {} already captured, skipping re-capture (Debezium redelivery)", parsed.id());
                return;
            }

            ProcessedEvent captured = new ProcessedEvent(parsed.id(), parsed.aggregateId(), parsed.eventType(),
                    Instant.now(), ProcessedEventStatus.CAPTURED, parsed.payloadJson());
            processedEventRepository.save(captured);

            CdcProgress progress = new CdcProgress(null, Instant.now());
            cdcProgressRepository.save(progress);
        });

        outboxEventPublisher.publish(parsed.id(), parsed.aggregateId(), parsed.eventType(), parsed.payloadJson());
    }
}
