package com.crypto.accountservice.infrastructure.client;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    public BoundedBody(int limit) { if(limit<=0)throw new IllegalArgumentException("INVALID_LIMIT");this.limit=limit; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription s) {
        if(subscription!=null || result.isDone()) { s.cancel();return; }
        subscription=s;s.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> chunks) {
        if(result.isDone())return;
        for(var chunk:chunks) {
            if(chunk.remaining()>limit-bytes.size()) { fail(new IllegalStateException("RESPONSE_TOO_LARGE"));return; }
            byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
        }
        subscription.request(1); // Kontrollü talep; sınırsız buffer büyümesi yok.
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    private void fail(Throwable error) { if(subscription!=null)subscription.cancel();result.completeExceptionally(error); }
    public synchronized void cancel() { fail(new CancellationException("REQUEST_CANCELLED")); }
}

