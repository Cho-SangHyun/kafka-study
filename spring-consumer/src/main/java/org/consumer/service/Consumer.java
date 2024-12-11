package org.consumer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.consumer.dto.TransferStatusDto;
import org.consumer.dto.MessageDto;
import org.consumer.repository.DelayedTransferRepository;
import org.consumer.util.ExternalApiClient;
import org.consumer.util.LockManager;
import org.consumer.util.TransferStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
@Component
@Slf4j
public class Consumer {
    private final static String TOPIC = "delay-transfer-topic";
    private final static String GROUP_ID = "group1";

    private final LockManager lockManager;
    private final ExternalApiClient externalApiClient;
    private final DelayedTransferRepository delayedTransferRepository;

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID)
    public void receive(ConsumerRecord<String, MessageDto> record) {
        final String userId = record.key();
        final MessageDto messageDto = record.value();
        log.info("Consumed message`s key: {}", userId);
        log.info("Consumed message`s value: {}", messageDto.toString());

        ReentrantLock userLock = lockManager.getUserLock(userId);
        userLock.lock();

        delayedTransferRepository.findById(messageDto.getId()).ifPresent(
                transferStatusDto -> {
                if (TransferStatus.PENDING.equals(transferStatusDto.getStatus())) {
                    externalApiClient.callTransferApi(
                        messageDto,
                        () -> {
                            log.info("Transfer is successful");
                            saveTransferStatus(transferStatusDto, TransferStatus.COMPLETED);
                        },
                        () -> {
                            log.info("Transfer is failed");
                            saveTransferStatus(transferStatusDto, TransferStatus.FAILED);
                        }
                    );
                }
            }
        );

        userLock.unlock();
    }

    private void saveTransferStatus(TransferStatusDto transferStatusDto,
                                    String status) {
        transferStatusDto.setStatus(status);
        delayedTransferRepository.save(transferStatusDto);
    }
}
