package com.shivaraj.orderservice.order;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot change order status from " + from + " to " + to
                + ". Allowed: " + from.allowedNext());
    }
}
