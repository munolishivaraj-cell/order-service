package com.shivaraj.orderservice.order;

import com.shivaraj.orderservice.order.OrderDtos.CreateOrderRequest;
import com.shivaraj.orderservice.order.OrderDtos.ItemRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Service unit tests with a mocked repository and an in-memory meter registry. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository repository;

    SimpleMeterRegistry meterRegistry;
    OrderService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new OrderService(repository, meterRegistry);
    }

    @Test
    void createSavesOrderAndIncrementsMetric() {
        when(repository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new CreateOrderRequest("cust-1",
                List.of(new ItemRequest("SKU-1", 3, new BigDecimal("2.00")))));

        assertThat(response.totalAmount()).isEqualByComparingTo("6.00");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(meterRegistry.get("orders.placed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void changeStatusRecordsTransitionMetric() {
        Order order = Order.create("cust-1", List.of(new OrderItem("SKU-1", 1, BigDecimal.ONE)));
        when(repository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        var response = service.changeStatus(order.getId(), OrderStatus.PAID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(meterRegistry.get("orders.status.changed")
                .tag("from", "CREATED").tag("to", "PAID").counter().count()).isEqualTo(1.0);
    }
}
