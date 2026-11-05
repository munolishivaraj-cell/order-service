package com.shivaraj.orderservice.order;

import com.shivaraj.orderservice.order.OrderDtos.CreateOrderRequest;
import com.shivaraj.orderservice.order.OrderDtos.OrderResponse;
import com.shivaraj.orderservice.order.OrderDtos.OrderSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final MeterRegistry meterRegistry;
    private final Counter ordersCreated;

    public OrderService(OrderRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        // Business metric, visible in Prometheus as orders_placed_total.
        // (Not "orders.created": the Prometheus client strips the reserved "_created" suffix.)
        this.ordersCreated = Counter.builder("orders.placed")
                .description("Number of orders created")
                .register(meterRegistry);
    }

    /** @Observed creates both a timer metric and a tracing span named "orders.create". */
    @Observed(name = "orders.create", contextualName = "create-order")
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        var items = request.items().stream()
                .map(i -> new OrderItem(i.productCode(), i.quantity(), i.unitPrice()))
                .toList();
        Order saved = repository.save(Order.create(request.customerId(), items));
        ordersCreated.increment();
        log.info("Order created id={} customer={} total={}", saved.getId(), saved.getCustomerId(), saved.getTotalAmount());
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return repository.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> list(String customerId, Pageable pageable) {
        Page<Order> page = (customerId == null || customerId.isBlank())
                ? repository.findAll(pageable)
                : repository.findByCustomerId(customerId, pageable);
        return page.map(OrderSummary::from);
    }

    @Observed(name = "orders.status-change", contextualName = "change-order-status")
    @Transactional
    public OrderResponse changeStatus(UUID id, OrderStatus next) {
        Order order = repository.findWithItemsById(id).orElseThrow(() -> new OrderNotFoundException(id));
        OrderStatus previous = order.getStatus();
        order.changeStatus(next);
        meterRegistry.counter("orders.status.changed", "from", previous.name(), "to", next.name()).increment();
        log.info("Order status changed id={} {} -> {}", id, previous, next);
        return OrderResponse.from(order);
    }
}
