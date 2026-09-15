package io.github.djyking.harness.adapters.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds response buffering before allocation grows, including chunked bodies. */
public final class LimitedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
  private final int limit;

  public LimitedBodyHandler(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Response limit must be positive");
    this.limit = limit;
  }

  @Override
  public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
    return new HttpResponse.BodySubscriber<>() {
      private final CompletableFuture<byte[]> result = new CompletableFuture<>();
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
      private Flow.Subscription subscription;

      @Override
      public CompletionStage<byte[]> getBody() {
        return result;
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (responseInfo.headers().firstValueAsLong("Content-Length").orElse(0) > limit) {
          subscription.cancel();
          result.completeExceptionally(
              new IllegalStateException("HTTP response size limit exceeded"));
        } else {
          subscription.request(1);
        }
      }

      @Override
      public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
          if (buffer.remaining() > limit - bytes.size()) {
            subscription.cancel();
            result.completeExceptionally(
                new IllegalStateException("HTTP response size limit exceeded"));
            return;
          }
          byte[] chunk = new byte[buffer.remaining()];
          buffer.get(chunk);
          bytes.writeBytes(chunk);
        }
        subscription.request(1);
      }

      @Override
      public void onError(Throwable error) {
        result.completeExceptionally(error);
      }

      @Override
      public void onComplete() {
        result.complete(bytes.toByteArray());
      }
    };
  }
}
