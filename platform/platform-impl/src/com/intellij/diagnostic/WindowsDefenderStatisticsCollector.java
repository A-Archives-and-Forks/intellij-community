// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic;

import com.intellij.internal.statistic.eventLog.EventLogGroup;
import com.intellij.internal.statistic.eventLog.events.EventFields;
import com.intellij.internal.statistic.eventLog.events.EventId1;
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class WindowsDefenderStatisticsCollector extends CounterUsagesCollector {
  private static final EventLogGroup GROUP = new EventLogGroup("defender", 2);

  private enum Status {Skipped, Enabled, Disabled, Error}
  private enum Reaction {Auto, Manual, ProjectMute, GlobalMute}

  private static final EventId1<Status> PROTECTION_CHECK_EVENT = GROUP.registerEvent("protection", EventFields.Enum("status", Status.class));
  private static final EventId1<Reaction> NOTIFICATION_EVENT = GROUP.registerEvent("notification", EventFields.Enum("reaction", Reaction.class));
  private static final EventId1<Boolean> AUTO_CONFIG_EVENT = GROUP.registerEvent("auto_config", EventFields.Boolean("success"));
  private static final EventId1<ExcludedScope> EXCLUDED_FROM_TRUST_DIALOG = GROUP.registerEvent("excluded_from_trust_dialog", EventFields.Enum("excluded_folders", ExcludedScope.class));

  private enum ExcludedScope {
    PROJECT_ONLY,
    PARENT_FOLDER_AND_IDE
  }
  
  static void protectionCheckSkipped(@NotNull Project project) {
    PROTECTION_CHECK_EVENT.log(project, Status.Skipped);
  }

  static void protectionCheckStatus(@NotNull Project project, @Nullable Boolean status) {
    PROTECTION_CHECK_EVENT.log(project, status == Boolean.TRUE ? Status.Enabled : status == Boolean.FALSE ? Status.Disabled : Status.Error);
  }

  static void auto(@NotNull Project project) {
    NOTIFICATION_EVENT.log(project, Reaction.Auto);
  }

  static void manual(@NotNull Project project) {
    NOTIFICATION_EVENT.log(project, Reaction.Manual);
  }

  static void suppressed(@NotNull Project project, boolean globally) {
    NOTIFICATION_EVENT.log(project, globally ? Reaction.GlobalMute : Reaction.ProjectMute);
  }

  static void configured(@NotNull Project project, boolean success) {
    AUTO_CONFIG_EVENT.log(project, success);
  }

  public static void excludedFromTrustDialog(boolean parentExcluded) {
    if (parentExcluded) {
      EXCLUDED_FROM_TRUST_DIALOG.log(ExcludedScope.PARENT_FOLDER_AND_IDE);
    } else {
      EXCLUDED_FROM_TRUST_DIALOG.log(ExcludedScope.PROJECT_ONLY);
    }
  }

  @Override
  public EventLogGroup getGroup() {
    return GROUP;
  }
}
