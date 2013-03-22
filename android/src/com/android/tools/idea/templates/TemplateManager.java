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
package com.android.tools.idea.templates;

import com.android.utils.XmlUtils;
import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.android.SdkConstants.*;
import static com.android.tools.idea.templates.Template.TEMPLATE_XML;

/**
 * Handles locating templates and providing template metadata
 */
public class TemplateManager {
  private static final Logger LOG = Logger.getInstance("#" + TemplateManager.class.getName());
  /**
   * Cache for {@link #getTemplate()}
   */
  private Map<File, TemplateMetadata> myTemplateMap;

  private static TemplateManager ourInstance = new TemplateManager();

  private TemplateManager() {
  }

  public static TemplateManager getInstance() {
    return ourInstance;
  }

  /**
   * @return the root folder containing templates
   */
  @Nullable
  public static File getTemplateRootFolder() {
    String location = AndroidSdkUtils.tryToChooseAndroidSdk().getLocation();
    if (location != null) {
      File folder = new File(location, FD_TOOLS + File.separator + FD_TEMPLATES);
      if (folder.isDirectory()) {
        return folder;
      }
    }

    return null;
  }

  /**
   * @return the root folder containing extra templates
   */
  @NotNull
  public static List<File> getExtraTemplateRootFolders() {
    List<File> folders = new ArrayList<File>();
    String location = null;
    if (location != null) {
      File extras = new File(location, FD_EXTRAS);
      if (extras.isDirectory()) {
        for (File vendor : TemplateUtils.listFiles(extras)) {
          if (!vendor.isDirectory()) {
            continue;
          }
          for (File pkg : TemplateUtils.listFiles(vendor)) {
            if (pkg.isDirectory()) {
              File folder = new File(pkg, FD_TEMPLATES);
              if (folder.isDirectory()) {
                folders.add(folder);
              }
            }
          }
        }

        // Legacy
        File folder = new File(extras, FD_TEMPLATES);
        if (folder.isDirectory()) {
          folders.add(folder);
        }
      }
    }

    return folders;
  }

  /**
   * Returns all the templates with the given prefix
   *
   * @param folder the folder prefix
   * @return the available templates
   */
  @NotNull
  public List<File> getTemplates(@NotNull String folder) {
    List<File> templates = new ArrayList<File>();
    Map<String, File> templateNames = Maps.newHashMap();
    File root = getTemplateRootFolder();
    if (root != null) {
      File[] files = new File(root, folder).listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) { // Avoid .DS_Store etc
            templates.add(file);
            templateNames.put(file.getName(), file);
          }
        }
      }
    }

    // Add in templates from extras/ as well.
    for (File extra : getExtraTemplateRootFolders()) {
      File[] files = new File(extra, folder).listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            File replaces = templateNames.get(file.getName());
            if (replaces != null) {
              int compare = compareTemplates(replaces, file);
              if (compare > 0) {
                int index = templates.indexOf(replaces);
                if (index != -1) {
                  templates.set(index, file);
                }
                else {
                  templates.add(file);
                }
              }
            }
            else {
              templates.add(file);
            }
          }
        }
      }
    }

    // Sort by file name (not path as is File's default)
    if (templates.size() > 1) {
      Collections.sort(templates, new Comparator<File>() {
        @Override
        public int compare(File file1, File file2) {
          return file1.getName().compareTo(file2.getName());
        }
      });
    }

    return templates;
  }

  /**
   * Compare two files, and return the one with the HIGHEST revision, and if
   * the same, most recently modified
   */
  private int compareTemplates(@NotNull File file1, @NotNull File file2) {
    TemplateMetadata template1 = getTemplate(file1);
    TemplateMetadata template2 = getTemplate(file2);

    if (template1 == null) {
      return 1;
    }
    else if (template2 == null) {
      return -1;
    }
    else {
      int delta = template2.getRevision() - template1.getRevision();
      if (delta == 0) {
        delta = (int)(file2.lastModified() - file1.lastModified());
      }
      return delta;
    }
  }

  @Nullable
  public TemplateMetadata getTemplate(@NotNull File templateDir) {
    if (myTemplateMap != null) {
      TemplateMetadata metadata = myTemplateMap.get(templateDir);
      if (metadata != null) {
        return metadata;
      }
    }
    else {
      myTemplateMap = Maps.newHashMap();
    }

    try {
      File templateFile = new File(templateDir, TEMPLATE_XML);
      if (templateFile.isFile()) {
        String xml = Files.toString(templateFile, Charsets.UTF_8);
        Document doc = XmlUtils.parseDocumentSilently(xml, true);
        if (doc != null && doc.getDocumentElement() != null) {
          TemplateMetadata metadata = new TemplateMetadata(doc);
          myTemplateMap.put(templateDir, metadata);
          return metadata;
        }
      }
    }
    catch (IOException e) {
      LOG.warn(e);
    }

    return null;
  }
}