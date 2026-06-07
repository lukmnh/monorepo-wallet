package com.gpay.payment.service.Impl;

import com.gpay.payment.client.WalletClient;
import com.gpay.payment.constant.TransactionStatus;
import com.gpay.payment.constant.TransactionType;
import com.gpay.payment.dto.PaymentDTO;
import com.gpay.payment.dto.PaymentDTO.TransactionResponse;
import com.gpay.payment.entity.Transactions;
import com.gpay.payment.entity.TransferRequest;
import com.gpay.payment.exception.PaymentException;
import com.gpay.payment.repository.TransactionRepository;
import com.gpay.payment.repository.TransferRequestRepository;
import com.gpay.payment.service.RateLimitService;
import com.gpay.payment.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferServiceImpl implements TransferService {
    private final TransactionRepository transactionRepository;
    private final TransferRequestRepository transferRequestRepository;
    private final WalletClient walletClient;
//    private final AuditClient auditClient;
    private final RateLimitService rateLimitService;

    @Override
    @Transactional
    public TransactionResponse transfer(UUID fromUserId, PaymentDTO.TransferRequest request, String idempotencyKey) {
//        long start = System.currentTimeMillis();

        if (fromUserId.equals(request.toUserId())) {
            throw new PaymentException.InvalidTransferException("Cannot transfer to yourself");
        }

        // Check daily transfer limit
        if (!rateLimitService.checkDailyTransferLimit(fromUserId, request.amount())) {
            throw new PaymentException.DailyLimitExceededException(
                    "Daily transfer limit exceeded. Remaining: "
                            + rateLimitService.getRemainingDailyLimit(fromUserId));
        }

        Transactions txn = Transactions.builder()
                .idempotencyKey(idempotencyKey)
                .userId(fromUserId)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .amount(request.amount())
                .description(request.description() != null ? request.description() : "Transfer")
                .traceId(MDC.get("traceId"))
                .build();
        txn = transactionRepository.save(txn);

        TransferRequest transferReq = TransferRequest.builder()
                .transaction(txn)
                .fromUserId(fromUserId)
                .toUserId(request.toUserId())
                .build();
        transferRequestRepository.save(transferReq);

        try {
            walletClient.atomicTransfer(
                    fromUserId,
                    request.toUserId(),
                    request.amount(),
                    txn.getId().toString(),
                    request.description()
            );
            txn.setStatus(TransactionStatus.SUCCESS);
            transactionRepository.save(txn);

            rateLimitService.incrementDailyTransfer(fromUserId, request.amount());

            log.info("Transfer SUCCESS txnId={} from={} to={} amount={}",
                    txn.getId(), fromUserId, request.toUserId(), request.amount());
        } catch (org.springframework.web.client.HttpClientErrorException.UnprocessableEntity e) {
            txn.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(txn);
            throw new PaymentException.InsufficientBalanceException("Insufficient balance");
        } catch (Exception e) {
            txn.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(txn);
            log.error("Transfer FAILED txnId={}: {}", txn.getId(), e.getMessage());
            throw new PaymentException.TransferFailedException("Transfer failed: " + e.getMessage());
        }

//        long duration = System.currentTimeMillis() - start;
//        auditClient.log(fromUserId, txn.getId(), "TRANSFER", txn.getStatus().name(),
//                java.util.Map.of("toUserId", request.toUserId(), "amount", request.amount()),
//                null, duration, null);

        return new TransactionResponse(txn.getId(), txn.getType(), txn.getStatus(),
                txn.getAmount(), txn.getDescription(), txn.getCreatedAt());
    }
}
