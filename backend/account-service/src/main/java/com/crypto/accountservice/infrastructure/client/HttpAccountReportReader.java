package com.crypto.accountservice.infrastructure.client;
import com.crypto.accountservice.application.port.AccountReportReader;
import com.crypto.accountservice.domain.model.AccountSummary;
import com.crypto.accountservice.domain.model.TradeHistory;
import com.crypto.accountservice.domain.exception.AccountFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static com.crypto.accountservice.domain.exception.AccountFailure.Code.*;
@Component
public class HttpAccountReportReader implements AccountReportReader {
    private final HttpClient http;
    private final ObjectMapper json;
    private final LoadBalancerClient loadBalancer;
    private final String serviceId;
    private final Duration timeout;
    private final int maxBytes;
    public HttpAccountReportReader(ObjectMapper json,LoadBalancerClient loadBalancer,
            @Value("${account.reporting-service-id:reporting-service}") String serviceId,
            @Value("${account.connect-timeout-ms:2000}") int connectMs,
            @Value("${account.request-timeout-ms:5000}") int requestMs,
            @Value("${account.max-response-bytes:1048576}") int maxBytes) {
        if (connectMs<=0 || requestMs<=0 || maxBytes<1024 || maxBytes>4*1024*1024
                || !"reporting-service".equals(serviceId)) throw new IllegalArgumentException("INVALID_REPORTING_CONFIG");
        this.json=json;this.loadBalancer=loadBalancer;this.serviceId=serviceId;
        this.timeout=Duration.ofMillis(requestMs);this.maxBytes=maxBytes;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public AccountSummary summary(String bearer,UUID session) {
        return get("/api/v1/reports/me/account-summary",bearer,session,null,null,AccountSummary.class);
    }
    public TradeHistory trades(String bearer,UUID session,String cursor,int limit) {
        return get("/api/v1/reports/me/trade-history",bearer,session,cursor,limit,TradeHistory.class);
    }
    private <T> T get(String path,String bearer,UUID session,String cursor,Integer limit,Class<T> type) {
        var instance=loadBalancer.choose(serviceId);
        if(instance==null)throw new AccountFailure(REPORT_UNAVAILABLE);
        var uri=UriComponentsBuilder.fromUriString("http://"+serviceId).replacePath(path);
        if(session!=null)uri.queryParam("sessionId",session);
        if(cursor!=null)uri.queryParam("cursor",cursor);
        if(limit!=null)uri.queryParam("limit",limit);
        var request=HttpRequest.newBuilder(loadBalancer.reconstructURI(instance,uri.build().encode().toUri())).timeout(timeout)
                .header("Authorization","Bearer "+bearer).header("Accept","application/json").GET().build();
        BoundedBody subscriber=new BoundedBody(maxBytes);
        var pending=http.sendAsync(request,info -> subscriber);
        try {
            // get timeout tüm response body tamamlanana kadar geçerlidir.
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            int status=response.statusCode();
            if(status==401)throw new AccountFailure(UNAUTHORIZED);
            if(status==404 || status==403)throw new AccountFailure(NOT_FOUND);
            if(status==400)throw new AccountFailure(INVALID_QUERY);
            if(status!=200)throw new AccountFailure(REPORT_UNAVAILABLE);
            return json.readValue(response.body(),type);
        } catch(AccountFailure e) { throw e; }
          catch(InterruptedException e) { subscriber.cancel();pending.cancel(true);Thread.currentThread().interrupt();throw new AccountFailure(REPORT_UNAVAILABLE); }
          catch(TimeoutException e) { subscriber.cancel();pending.cancel(true);throw new AccountFailure(REPORT_UNAVAILABLE); }
          catch(ExecutionException e) {
              throw new AccountFailure(e.getCause() instanceof java.io.IOException ? REPORT_UNAVAILABLE : INVALID_REPORT);
          }
          catch(java.io.IOException e) { throw new AccountFailure(INVALID_REPORT); }
    }
}

