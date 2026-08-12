package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

/** Auditable contract shared by every executable intent type. */
public record IntentConversationContract(
        PublicResponseContract publicResponse,
        ClarificationContract clarification,
        PendingStateContract pendingState,
        MutationContract mutation,
        RegressionContract regression) {

    public static IntentConversationContract standard() {
        return new IntentConversationContract(
                PublicResponseContract.FINAL_TYPED_BOUNDARY,
                ClarificationContract.EXACTLY_ONE_TYPED_QUESTION,
                PendingStateContract.ACTOR_SCOPE_TYPED_POINTER,
                MutationContract.JAVA_VALIDATED_EXPLICIT_CONFIRMATION,
                RegressionContract.CATALOG_AND_HANDLER_REQUIRED);
    }

    public enum PublicResponseContract { FINAL_TYPED_BOUNDARY }
    public enum ClarificationContract { EXACTLY_ONE_TYPED_QUESTION }
    public enum PendingStateContract { ACTOR_SCOPE_TYPED_POINTER }
    public enum MutationContract { JAVA_VALIDATED_EXPLICIT_CONFIRMATION }
    public enum RegressionContract { CATALOG_AND_HANDLER_REQUIRED }
}
