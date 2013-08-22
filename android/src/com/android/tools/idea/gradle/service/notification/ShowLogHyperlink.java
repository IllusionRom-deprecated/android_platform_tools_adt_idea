/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.gradle.service.notification;

import com.intellij.ide.actions.ShowFilePathAction;
import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NonNls;

import java.io.File;

class ShowLogHyperlink extends NotificationHyperlink {
  @NonNls private static final String IDEA_LOG_FILE_NAME = "idea.log";

  ShowLogHyperlink() {
    super("showLogFile", "Show log file");
  }

  @Override
  void execute() {
    File logFile = new File(PathManager.getLogPath(), IDEA_LOG_FILE_NAME);
    ShowFilePathAction.openFile(logFile);
  }
}