package com.shivaraj.orderservice.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Loads items in the same query to avoid N+1 selects. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    Page<Order> findByCustomerId(String customerId, Pageable pageable);
}
