package com.shivaraj.orderservice.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request / response records for the REST API. Entities never leave the service layer. */
public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotBlank @Size(max = 64) String customerId,
            @NotEmpty @Size(max = 50) List<@Valid ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank @Size(max = 64) String productCode,
            @Min(1) @Max(1000) int quantity,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
    }

    public record UpdateStatusRequest(@NotNull OrderStatus status) {
    }

    public record ItemResponse(String productCode, int quantity, BigDecimal unitPrice) {
        static ItemResponse from(OrderItem item) {
            return new ItemResponse(item.getProductCode(), item.getQuantity(), item.getUnitPrice());
        }
    }

    public record OrderResponse(
            UUID id,
            String customerId,
            OrderStatus status,
            BigDecimal totalAmount,
            List<ItemResponse> items,
            Instant createdAt,
            Instant updatedAt) {

        static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getCustomerId(),
                    order.getStatus(),
                    order.getTotalAmount(),
                    order.getItems().stream().map(ItemResponse::from).toList(),
                    order.getCreatedAt(),
                    order.getUpdatedAt());
        }
    }

    /** Summary without items, used for list endpoints. */
    public record OrderSummary(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
        static OrderSummary from(Order order) {
            return new OrderSummary(order.getId(), order.getCustomerId(), order.getStatus(),
                    order.getTotalAmount(), order.getCreatedAt());
        }
    }
}
