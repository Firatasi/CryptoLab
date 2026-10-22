package com.crypto.strategyservice.infrastructure.client.fixture;

import com.crypto.strategyservice.application.port.ExplanationClient;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class FixtureExplanationClient implements ExplanationClient {

    private volatile Function<Request, String> behavior = req ->
            "FIXTURE_EXPLANATION[" + req.action() + ":" + req.policyReason() + "]";

    public void setBehavior(Function<Request, String> behavior) {
        this.behavior = behavior;
    }

    public void reset() {
        this.behavior = req -> "FIXTURE_EXPLANATION[" + req.action() + ":" + req.policyReason() + "]";
    }

    @Override
    public String explain(Request request) {
        return behavior.apply(request);
    }
}

