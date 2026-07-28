package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusDirective;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationInboundIdempotency;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectConversationFocusBindingFactory;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectScopeFromFocusService;
import com.aproject.aidriven.mymobilesecretary.project.application.ProjectService;
import com.aproject.aidriven.mymobilesecretary.project.domain.Project;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectStatus;
import com.aproject.aidriven.mymobilesecretary.project.domain.ProjectType;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Executes Project lifecycle commands and delegates mode aliases to the global focus contract. */
@Component
public final class ProjectIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED = Set.of(
            IntentCommand.Type.CREATE_PROJECT,
            IntentCommand.Type.OPEN_PROJECT_EDIT_MODE,
            IntentCommand.Type.SWITCH_PROJECT_EDIT_MODE,
            IntentCommand.Type.RESUME_PROJECT_EDIT_MODE,
            IntentCommand.Type.CLOSE_PROJECT_EDIT_MODE,
            IntentCommand.Type.SHOW_PROJECT_OVERVIEW,
            IntentCommand.Type.COMPLETE_PROJECT,
            IntentCommand.Type.REOPEN_PROJECT,
            IntentCommand.Type.ARCHIVE_PROJECT);

    private final ProjectService projects;
    private final ProjectScopeFromFocusService scopes;
    private final ProjectConversationFocusBindingFactory focusBindings;

    public ProjectIntentHandler(
            ProjectService projects,
            ProjectScopeFromFocusService scopes,
            ProjectConversationFocusBindingFactory focusBindings) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.focusBindings = Objects.requireNonNull(focusBindings, "focusBindings");
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case CREATE_PROJECT -> create(command);
            case OPEN_PROJECT_EDIT_MODE, SWITCH_PROJECT_EDIT_MODE, RESUME_PROJECT_EDIT_MODE ->
                select(command, this::mode);
            case CLOSE_PROJECT_EDIT_MODE -> close(command);
            case SHOW_PROJECT_OVERVIEW -> select(command, this::overview);
            case COMPLETE_PROJECT -> select(command, this::complete);
            case REOPEN_PROJECT -> select(command, this::reopen);
            case ARCHIVE_PROJECT -> select(command, this::archive);
            default -> throw new IllegalArgumentException(
                    "unsupported Project intent " + command.type());
        };
    }

    private IntentResult create(IntentCommand command) {
        if (blank(command.title())) {
            return IntentResult.clarificationNeeded("請告訴我要建立的旅行專案名稱。");
        }
        Project project = projects.createProject(
                ProjectType.TRAVEL,
                command.title(),
                ConversationInboundIdempotency.fromRequestId(
                        RequestCorrelationContext.currentId()));
        return IntentResult.message(
                        IntentResult.Action.PROJECT_CREATED,
                        "已建立旅行專案「%s」。".formatted(project.getName()))
                .withFocusBinding(focusBindings.from(project));
    }

    private IntentResult close(IntentCommand command) {
        if (!blank(command.title())) {
            return IntentResult.clarificationNeeded(
                    "你要離開專案模式、完成專案、封存專案，還是取消專案裡的某個項目？"
                            + "這次尚未變更任何資料。");
        }
        return IntentResult.message(
                        IntentResult.Action.PROJECT_MODE_INFO,
                        "只離開目前的專案模式；既有專案不會完成、封存或刪除。")
                .withFocusDirective(ConversationFocusDirective.FOCUS_CONTROL_ONLY);
    }

    private IntentResult select(
            IntentCommand command, Function<Project, IntentResult> operation) {
        if (blank(command.title())) {
            try {
                return operation.apply(projects.getProject(scopes.requireActive().projectId()));
            } catch (RuntimeException unavailable) {
                return IntentResult.clarificationNeeded(
                        "目前沒有可唯一使用的專案；請告訴我專案名稱。");
            }
        }
        List<Project> candidates = candidates(command.title());
        if (candidates.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "找不到可使用的「%s」專案；請提供更完整名稱。".formatted(command.title()));
        }
        if (candidates.size() > 1) {
            String choices = candidates.stream()
                    .map(project -> "「%s」（%s）".formatted(
                            project.getName(), statusLabel(project.getStatus())))
                    .distinct()
                    .limit(5)
                    .collect(java.util.stream.Collectors.joining("、"));
            return IntentResult.clarificationNeeded(
                    "找到多個符合的專案：%s。請提供更完整名稱；這次尚未變更任何資料。"
                            .formatted(choices));
        }
        return operation.apply(candidates.getFirst());
    }

    private List<Project> candidates(String selector) {
        String normalizedSelector = normalize(selector);
        List<Project> selectable = projects.listProjects().stream()
                .filter(project -> project.getStatus() != ProjectStatus.ARCHIVED)
                .filter(project -> {
                    String name = normalize(project.getName());
                    return name.equals(normalizedSelector)
                            || name.contains(normalizedSelector)
                            || normalizedSelector.contains(name);
                })
                .sorted(Comparator.comparing(Project::getName)
                        .thenComparing(Project::getStatus)
                        .thenComparing(Project::getId))
                .toList();
        List<Project> exact = selectable.stream()
                .filter(project -> normalize(project.getName()).equals(normalizedSelector))
                .toList();
        return exact.isEmpty() ? selectable : exact;
    }

    private IntentResult mode(Project project) {
        return IntentResult.message(
                        IntentResult.Action.PROJECT_MODE_INFO,
                        "已選擇專案「%s」。".formatted(project.getName()))
                .withFocusBinding(focusBindings.from(project));
    }

    private IntentResult overview(Project project) {
        return IntentResult.message(
                        IntentResult.Action.PROJECT_OVERVIEW,
                        "專案「%s」目前是%s。".formatted(
                                project.getName(), statusLabel(project.getStatus())))
                .withFocusBinding(focusBindings.from(project));
    }

    private IntentResult complete(Project project) {
        Project completed = projects.completeProject(project.getId());
        return IntentResult.message(
                IntentResult.Action.PROJECT_COMPLETED,
                "已將專案「%s」標示為完成；目前專案模式會保留。".formatted(completed.getName()));
    }

    private IntentResult reopen(Project project) {
        Project reopened = projects.reopenProject(project.getId());
        return IntentResult.message(
                IntentResult.Action.PROJECT_REOPENED,
                "已重新開啟專案「%s」。".formatted(reopened.getName()));
    }

    private IntentResult archive(Project project) {
        Project archived = projects.archiveProject(project.getId());
        return IntentResult.message(
                        IntentResult.Action.PROJECT_ARCHIVED,
                        "已封存專案「%s」。".formatted(archived.getName()))
                .withFocusDirective(
                        focusBindings.from(archived),
                        ConversationFocusDirective.INVALIDATE_TARGET);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Z}\\p{P}\\p{S}]+", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String statusLabel(ProjectStatus status) {
        return switch (status) {
            case ACTIVE -> "進行中";
            case COMPLETED -> "已完成";
            case ARCHIVED -> "已封存";
        };
    }
}
