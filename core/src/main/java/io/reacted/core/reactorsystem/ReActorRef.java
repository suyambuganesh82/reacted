/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactorsystem;

import io.reacted.core.messages.AckingPolicy;
import io.reacted.core.messages.SerializationUtils;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactors.ReActorId;
import io.reacted.core.reactors.systemreactors.Ask;
import io.reacted.patterns.NonNullByDefault;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@NonNullByDefault
public final class ReActorRef implements Externalizable {
    public static final ReActorRef NO_REACTOR_REF = new ReActorRef(ReActorId.NO_REACTOR_ID,
                                                                   NullReActorSystemRef.NULL_REACTOR_SYSTEM_REF);
    public static final int NO_REACTOR_REF_MARKER = 1;
    public static final int COMMON_REACTOR_REF_MARKER = 0;
    @Serial
    private static final long serialVersionUID = 1;
    private static final long REACTOR_ID_OFFSET = SerializationUtils.getFieldOffset(ReActorRef.class, "reActorId")
                                                                    .orElseSneakyThrow();
    private static final long HASHCODE_OFFSET = SerializationUtils.getFieldOffset(ReActorRef.class, "hashCode")
                                                                  .orElseSneakyThrow();
    private static final long REACTORSYSTEMREF_OFFSET = SerializationUtils.getFieldOffset(ReActorRef.class,
                                                                                          "reActorSystemRef")
                                                                          .orElseSneakyThrow();
    private static final Duration NO_TIMEOUT = Duration.ofDays(Integer.MAX_VALUE);

    private final ReActorId reActorId;
    private final int hashCode;
    private final ReActorSystemRef reActorSystemRef;

    public ReActorRef() {
        this.reActorId = ReActorId.NO_REACTOR_ID;
        this.hashCode = Integer.MIN_VALUE;
        this.reActorSystemRef = NullReActorSystemRef.NULL_REACTOR_SYSTEM_REF;
    }
    public ReActorRef(ReActorId reActorId, ReActorSystemRef reActorSystemRef) {
        this.reActorId = reActorId;
        this.reActorSystemRef = reActorSystemRef;
        this.hashCode = Objects.hash(reActorId, reActorSystemRef);
    }

    public ReActorSystemRef getReActorSystemRef() { return reActorSystemRef; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReActorRef that = (ReActorRef) o;
        return Objects.equals(getReActorId(), that.getReActorId()) &&
               Objects.equals(getReActorSystemRef(), that.getReActorSystemRef());
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "ReActorRef{" + "reActorId=" + reActorId + ", hashCode=" + hashCode + ", reActorSystemRef=" + reActorSystemRef + '}';
    }

    /**
     * Sends a message to this ReActor using {@link ReActorSystem#getSystemSink()} as source
     *
     * @param messagePayload payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A {@link DeliveryStatus} representing the outcome of the operation. Different drivers
     * may offer different guarantees regarding the returned {@link DeliveryStatus}. The common
     * baseline for this method is providing delivery guarantee to the local driver bus
     */
    public <PayloadT extends Serializable> DeliveryStatus publish(PayloadT messagePayload) {
        return reActorSystemRef.publish(ReActorRef.NO_REACTOR_REF, this,
                                        Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to this ReActor
     *
     * @param msgSender      source of the message
     * @param messagePayload payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A {@link DeliveryStatus} representing the outcome of the operation. Different drivers
     * may offer different guarantees regarding the returned {@link DeliveryStatus}. The common
     * baseline for this method is providing delivery guarantee to the local driver bus
     */
    public <PayloadT extends Serializable> DeliveryStatus publish(ReActorRef msgSender,
                                                                  PayloadT messagePayload) {
        return reActorSystemRef.publish(Objects.requireNonNull(msgSender), this,
                                        Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to this ReActor. All the subscribers for this message type will not be notified.
     * @see io.reacted.core.typedsubscriptions.TypedSubscription
     *
     * @param msgSender      source of the message
     * @param messagePayload payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A {@link DeliveryStatus} representing the outcome of the operation. Different drivers
     * may offer different guarantees regarding the returned {@link DeliveryStatus}. The common
     * baseline for this method is providing delivery guarantee to the local driver bus
     */
    public <PayloadT extends Serializable> DeliveryStatus tell(ReActorRef msgSender, PayloadT messagePayload) {
        return reActorSystemRef.tell(Objects.requireNonNull(msgSender), this,
                                     Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to this ReActor using {@link ReActorRef#NO_REACTOR_REF} as source.
     * All the subscribers for this message type will not be notified.
     * @see io.reacted.core.typedsubscriptions.TypedSubscription
     *
     * @param messagePayload payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A {@link DeliveryStatus} representing the outcome of the operation. Different drivers
     * may offer different guarantees regarding the returned {@link DeliveryStatus}. The common
     * baseline for this method is providing delivery guarantee to the local driver bus
     */
    public <PayloadT extends Serializable> DeliveryStatus  tell(PayloadT messagePayload) {
        return reActorSystemRef.tell(ReActorRef.NO_REACTOR_REF, this,
                                     Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to this ReActor requiring an ack as a confirmation of the delivery into the target reactor's
     * mailbox. All the subscribers for {@code PayloadT} type will not be notified.
     * @see io.reacted.core.typedsubscriptions.TypedSubscription
     *
     * @param msgSender      message source
     * @param messagePayload message payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A {@link CompletionStage} that is going to be completed when an ack from the destination reactor system
     * is received containing the outcome of the delivery of the message into the target actor mailbox
     */
    public <PayloadT extends Serializable> CompletionStage<DeliveryStatus> atell(ReActorRef msgSender, PayloadT messagePayload) {
        return reActorSystemRef.atell(Objects.requireNonNull(msgSender), this, AckingPolicy.ONE_TO_ONE,
                                      Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to a this ReActor requiring an ack as a confirmation of the delivery into the target reactor's
     * mailbox. The sender of the message is going to be the system sink
     *
     * @param messagePayload message payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A completable future that is going to be completed when an ack from the destination reactor system
     * is received containing the outcome of the delivery of the message into the target actor mailbox
     */
    public <PayloadT extends Serializable> CompletionStage<DeliveryStatus> apublish(PayloadT messagePayload) {
        return reActorSystemRef.apublish(ReActorRef.NO_REACTOR_REF, this, AckingPolicy.ONE_TO_ONE,
                                         Objects.requireNonNull(messagePayload));
    }

    /**
     * Sends a message to a this ReActor requiring an ack as a confirmation of the delivery into the target reactor's
     * mailbox
     *
     * @param msgSender      message source
     * @param messagePayload message payload
     * @param <PayloadT> Any {@link Serializable} object
     * @return A completable future that is going to be completed when an ack from the destination reactor system
     * is received containing the outcome of the delivery of the message into the target actor mailbox
     */
    public <PayloadT extends Serializable> CompletionStage<DeliveryStatus> apublish(ReActorRef msgSender,
                                                                                    PayloadT messagePayload) {
        return reActorSystemRef.apublish(Objects.requireNonNull(msgSender), this, AckingPolicy.ONE_TO_ONE,
                                         Objects.requireNonNull(messagePayload));
    }

    /**
     * Send a message to this reactor and return its reply.
     *
     * @param request       payload that is being sent to this reactor
     * @param expectedReply expected message type as reply to this request
     * @param requestName   name of the request. It must be unique reactor name in the reactor system as long as this
     *                      ask is alive
     * @param <ReplyT> Any {@link Serializable} object
     * @param <RequestT> Any {@link Serializable} object
     * @return A {@link CompletionStage} that is going to be completed once an answer for the request has been received.
     * On failure, the {@link CompletionStage} will be a failed {@link CompletionStage} contain the cause of the failure
     */
    public <ReplyT extends Serializable, RequestT extends Serializable>
    CompletionStage<ReplyT> ask(RequestT request, Class<ReplyT> expectedReply,
                                String requestName) {
        return ask(getReActorSystemRef().getBackingDriver().getLocalReActorSystem(), this,
                   Objects.requireNonNull(request), Objects.requireNonNull(expectedReply),
                   NO_TIMEOUT, requestName);
    }

    /**
     * Send a message to this reactor and return its reply.
     *
     * @param request       payload that is being sent to this reactor
     * @param expectedReply expected message type as reply to this request
     * @param expireTimeout mark this request as completed and failed after this timeout
     * @param requestName   name of the request. It must be unique reactor name in the reactor system as long as this
     *                      ask is alive
     * @param <ReplyT> Any {@link Serializable} object
     * @param <RequestT> Any {@link Serializable} object
     * @return A {@link CompletionStage} that is going to be completed once an answer for the request has been received.
     * On failure, the {@link CompletionStage} will be a failed {@link CompletionStage} contain the cause of the failure
     */
    public <ReplyT extends Serializable, RequestT extends Serializable>
    CompletionStage<ReplyT> ask(RequestT request, Class<ReplyT> expectedReply, Duration expireTimeout,
                                String requestName) {
        return ask(Objects.requireNonNull(getReActorSystemRef().getBackingDriver().getLocalReActorSystem()),
                   this, Objects.requireNonNull(request), Objects.requireNonNull(expectedReply),
                   Objects.requireNonNull(expireTimeout), Objects.requireNonNull(requestName));
    }

    public ReActorId getReActorId() { return reActorId; }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        if (this == NO_REACTOR_REF) {
            out.writeInt(NO_REACTOR_REF_MARKER);
        } else {
            out.writeInt(COMMON_REACTOR_REF_MARKER);
            getReActorId().writeExternal(out);
            getReActorSystemRef().writeExternal(out);
        }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        if (in.readInt() == COMMON_REACTOR_REF_MARKER) {
            ReActorId receivedReActorId = new ReActorId();
            receivedReActorId.readExternal(in);
            setReActorId(receivedReActorId);
            ReActorSystemRef receivedReActorSystemRef = new ReActorSystemRef();
            receivedReActorSystemRef.readExternal(in);
            setReActorSystemRef(receivedReActorSystemRef);
            setHashCode(Objects.hash(getReActorId(), getReActorSystemRef()));
        }
    }

    private static <ReplyT extends Serializable, RequestT extends Serializable>
    CompletionStage<ReplyT> ask(ReActorSystem localReActorSystem, ReActorRef target, RequestT request,
                                Class<ReplyT> expectedReplyType, Duration askTimeout, String requestName) {
        CompletableFuture<ReplyT> returnValue = new CompletableFuture<>();
        localReActorSystem.spawn(new Ask<>(askTimeout, expectedReplyType, returnValue, requestName, target, request))
                          .ifError(returnValue::completeExceptionally);
        return returnValue;
    }
    private void setReActorId(ReActorId reActorId) {
        SerializationUtils.setObjectField(this, REACTOR_ID_OFFSET, reActorId);
    }
    private void setReActorSystemRef(ReActorSystemRef reActorSystemRef) {
        SerializationUtils.setObjectField(this, REACTORSYSTEMREF_OFFSET, reActorSystemRef);
    }
    public ReActorRef setHashCode(int hashCode) {
        SerializationUtils.setIntField(this, HASHCODE_OFFSET, hashCode);
        return this;
    }
}
