package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.UUID;

/** Request-local reference resolved from a tenant-scoped server-side LINE quote. */
public final class TrustedConversationReferenceContext {

    private static final ThreadLocal<References> REFERENCES = new ThreadLocal<>();

    private TrustedConversationReferenceContext() {}

    public static Scope openMaterializationProposal(UUID proposalId) {
        References current = REFERENCES.get();
        return open(proposalId, current == null ? null : current.mediaId());
    }

    public static Scope open(UUID proposalId, Long mediaId) {
        References previous = REFERENCES.get();
        if (proposalId == null && mediaId == null) {
            REFERENCES.remove();
        } else {
            REFERENCES.set(new References(proposalId, mediaId));
        }
        return new Scope(previous);
    }

    public static UUID currentMaterializationProposalId() {
        References references = REFERENCES.get();
        return references == null ? null : references.materializationProposalId();
    }

    public static Long currentMediaId() {
        References references = REFERENCES.get();
        return references == null ? null : references.mediaId();
    }

    public static final class Scope implements AutoCloseable {
        private final References previous;
        private boolean closed;

        private Scope(References previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                REFERENCES.remove();
            } else {
                REFERENCES.set(previous);
            }
        }
    }

    private record References(UUID materializationProposalId, Long mediaId) {}
}
