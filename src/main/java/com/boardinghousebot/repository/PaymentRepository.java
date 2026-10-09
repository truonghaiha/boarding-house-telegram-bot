package com.boardinghousebot.repository;

import com.boardinghousebot.domain.invoice.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("SELECT p FROM Payment p LEFT JOIN FETCH p.invoice i WHERE i.id = :invoiceId ORDER BY p.paymentDate DESC")
    List<Payment> findByInvoiceIdOrderByPaymentDateDesc(@Param("invoiceId") Long invoiceId);
}
