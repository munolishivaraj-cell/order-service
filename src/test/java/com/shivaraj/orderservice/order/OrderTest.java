package com.shivaraj.orderservice.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for domain rules: no Spring context, runs in milliseconds. */
class OrderTest {

    private Order newOrder() {
        return Order.create("cust-1", List.of(
                new OrderItem("SKU-1", 2, new BigDecimal("10.50")),
                new OrderItem("SKU-2", 1, new BigDecimal("5.00"))));
    }

    @Test
    void createCalculatesTotalAndStartsAsCreated() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("26.00");
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getId()).isNotNull();
    }

    @Test
    void followsHappyPathLifecycle() {
        Order order = newOrder();

        order.changeStatus(OrderStatus.PAID);
        order.changeStatus(OrderStatus.SHIPPED);
        order.changeStatus(OrderStatus.DELIVERED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, SHIPPED",
            "CREATED, DELIVERED",
            "PAID, DELIVERED",
            "SHIPPED, CANCELLED",
    })
    void rejectsIllegalTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canMoveTo(to)).isFalse();
    }

    @Test
    void terminalStatusesCannotChange() {
        Order order = newOrder();
        order.changeStatus(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> order.changeStatus(OrderStatus.PAID))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("CANCELLED");
    }
}
