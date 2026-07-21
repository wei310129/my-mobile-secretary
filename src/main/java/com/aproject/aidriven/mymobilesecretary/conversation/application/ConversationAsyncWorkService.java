package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationAsyncWork;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationAsyncWorkRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts durable async work and emits terminal notifications without changing any current focus. */
@Service
public class ConversationAsyncWorkService {
    private final ConversationScopeResolver resolver; private final ConversationAsyncWorkRepository works;
    private final ConversationFocusAtomicExecutor executor; private final NotificationPublisher notifications;
    private final Clock clock;
    public ConversationAsyncWorkService(ConversationScopeResolver resolver, ConversationAsyncWorkRepository works,
                                        ConversationFocusAtomicExecutor executor, NotificationPublisher notifications,
                                        Clock clock) { this.resolver=resolver; this.works=works; this.executor=executor; this.notifications=notifications; this.clock=clock; }

    @Transactional
    public AsyncWorkStart start(String workType, UUID workflowId, String safeLabel, String inboundHmac) {
        WorkspaceContext context=WorkspaceContextHolder.requireContext(); ConversationScopeKey scope=resolver.current(context);
        ConversationAsyncWork existing=works.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(context.workspaceId(),context.actorId(),context.channel(),scope.digest(),inboundHmac).orElse(null);
        if(existing!=null)return new AsyncWorkStart(existing.getId(), reply(safeLabel));
        ConversationAsyncWork work=ConversationAsyncWork.pending(scope,context.channel(),workType,workflowId,safeLabel,inboundHmac,Instant.now(clock));
        FocusResponseEnvelope response=executor.execute(FocusDecision.transition(FocusTransitionType.ENTER),new FocusControl.EnterAsyncWork("ASYNC_WORK",workflowId,safeLabel),inboundHmac,notice(safeLabel),()->{ works.saveAndFlush(work); return FocusResponseEnvelope.withoutNotice("已開始處理「"+safeLabel+"」，完成後會通知你。"); });
        return new AsyncWorkStart(work.getId(),response);
    }

    @Transactional public boolean complete(UUID workId,String publicMessage){return terminal(workId,publicMessage,true);}
    @Transactional public boolean fail(UUID workId,String publicMessage){return terminal(workId,publicMessage,false);}
    private boolean terminal(UUID workId,String publicMessage,boolean success){ WorkspaceContext context=WorkspaceContextHolder.requireContext(); ConversationAsyncWork work=works.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(workId,context.workspaceId(),context.actorId()).orElse(null); if(work==null)return false; boolean changed=success?work.succeed(Instant.now(clock)):work.fail(Instant.now(clock)); if(!changed)return false; String message=requiredPublicMessage(publicMessage); notifications.enqueue(new NotificationRequest(context.actorId(),"conversation-async:"+work.getId()+":"+(success?"SUCCESS":"FAILURE"),null,null,success?"處理完成":"處理未完成","關於「"+work.getSafeLabel()+"」："+message)); return true; }
    private FocusResponseEnvelope reply(String label){ return FocusResponseEnvelope.withNotice("已開始處理「"+label+"」，完成後會通知你。",notice(label),new ConversationFocusReplyDecorator(new FocusTransitionNoticeRenderer())); }
    private static FocusTransitionNotice notice(String label){return FocusTransitionNotice.forTransition(FocusTransitionType.ENTER,null,label,null);}
    private static String requiredPublicMessage(String message){String value=Objects.requireNonNull(message,"publicMessage").strip();if(value.isEmpty()||value.length()>1000)throw new IllegalArgumentException("public message is required");return value;}
    public record AsyncWorkStart(UUID jobId,FocusResponseEnvelope reply) { }
}
