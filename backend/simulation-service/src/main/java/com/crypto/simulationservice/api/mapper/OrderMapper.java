package com.crypto.simulationservice.api.mapper;

import org.springframework.stereotype.Component;

import com.crypto.simulationservice.api.dto.request.SubmitOrderRequest;
import com.crypto.simulationservice.api.dto.response.AcceptedOrderResponse;
import com.crypto.simulationservice.application.command.SubmitOrderCommand;
import com.crypto.simulationservice.application.service.SubmitOrder;

@Component
public class OrderMapper {

    public SubmitOrderCommand toCommand(SubmitOrderRequest request) {
        // Mapper fiyat/quantity hesaplamaz, owner üretmez; yalnız biçim dönüştürür.
        return new SubmitOrderCommand(request.planId(), request.revision(), request.planHash());
    }

    public AcceptedOrderResponse toResponse(SubmitOrder.Accepted result) {
        return new AcceptedOrderResponse(result.commandId(), result.status(),
                result.statusUrl(), result.acceptedAt());
    }
}
