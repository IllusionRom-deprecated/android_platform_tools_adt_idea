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

package com.android.tools.idea.refactoring.rtl;

import com.android.SdkConstants;
import com.android.resources.ResourceFolderType;
import com.android.xml.AndroidManifest;
import com.google.common.collect.Maps;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomManager;
import org.jetbrains.android.dom.layout.LayoutDomFileDescription;
import org.jetbrains.android.dom.layout.LayoutViewElement;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.android.SdkConstants.*;
import static com.android.tools.idea.refactoring.rtl.RtlRefactoringUsageInfo.RtlRefactoringType.LAYOUT_FILE_ATTRIBUTE;
import static com.android.xml.AndroidManifest.NODE_APPLICATION;
import static com.android.xml.AndroidManifest.NODE_USES_SDK;
import static com.android.xml.AndroidManifest.ATTRIBUTE_SUPPORTS_RTL;
import static com.android.xml.AndroidManifest.ATTRIBUTE_TARGET_SDK_VERSION;

import static com.android.tools.idea.refactoring.rtl.RtlRefactoringUsageInfo.RtlRefactoringType.MANIFEST_SUPPORTS_RTL;
import static com.android.tools.idea.refactoring.rtl.RtlRefactoringUsageInfo.RtlRefactoringType.MANIFEST_TARGET_SDK;

public class RtlSupportProcessor extends BaseRefactoringProcessor {

    private static final Logger LOG = Logger.getInstance("#com.android.tools.idea.refactoring.AddRTLSupportProcessor");
    private static final String REFACTORING_NAME = AndroidBundle.message("android.refactoring.rtl.addsupport.title");

    public static final String RES_V_QUALIFIER = "-v";
    public static final String RES_V17_QUALIFIER = "-v17";

    final private RtlSupportProperties myProperties;
    final private Project myProject;

    // This is the API level corresponding to the first public release for RTL support
    final static int RTL_TARGET_SDK_START = 17;

    private static Map<String, String> ourMapMirroredAttributeName = Maps.newHashMapWithExpectedSize(12);

    static {
        initMapMirroredAttributes();
    }

    private static void initMapMirroredAttributes() {
        ourMapMirroredAttributeName.put(ATTR_PADDING_LEFT, ATTR_PADDING_START);
        ourMapMirroredAttributeName.put(ATTR_PADDING_RIGHT, ATTR_PADDING_END);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_MARGIN_LEFT, ATTR_LAYOUT_MARGIN_START);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_MARGIN_RIGHT, ATTR_LAYOUT_MARGIN_END);
        ourMapMirroredAttributeName.put(ATTR_DRAWABLE_LEFT, ATTR_DRAWABLE_START);
        ourMapMirroredAttributeName.put(ATTR_DRAWABLE_RIGHT, ATTR_DRAWABLE_END);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_TO_LEFT_OF, ATTR_LAYOUT_TO_START_OF);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_TO_RIGHT_OF, ATTR_LAYOUT_TO_END_OF);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_ALIGN_LEFT, ATTR_LAYOUT_ALIGN_START);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_ALIGN_RIGHT, ATTR_LAYOUT_ALIGN_END);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_ALIGN_PARENT_LEFT, ATTR_LAYOUT_ALIGN_PARENT_START);
        ourMapMirroredAttributeName.put(ATTR_LAYOUT_ALIGN_PARENT_RIGHT, ATTR_LAYOUT_ALIGN_PARENT_END);

        // Gravity is a special case that we will handled separately as we will mirror its value instead of its name
    }

    protected RtlSupportProcessor(Project project, @NotNull RtlSupportProperties properties) {
        super(project);
        myProject = project;
        myProperties = properties;
        setPreviewUsages(true);
    }

    @NotNull
    @Override
    protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
        return new RtlSupportUsageViewDescriptor(myProperties);
    }

    @NotNull
    @Override
    protected UsageInfo[] findUsages() {
        if (!myProperties.hasSomethingToDo()) {
            return UsageInfo.EMPTY_ARRAY;
        }
        final List<UsageInfo> list = new ArrayList<UsageInfo>();

        if (myProperties.updateAndroidManifest) {
            addManifestRefactoring(list);
        }

        if (myProperties.updateLayouts) {
            addLayoutRefactoring(list);
        }

        final int size = list.size();
        return list.toArray(new UsageInfo[size]);
    }

    @Override
    protected void performRefactoring(UsageInfo[] usages) {
        for (UsageInfo usageInfo : usages) {
            RtlRefactoringUsageInfo refactoring = (RtlRefactoringUsageInfo)usageInfo;
            switch (refactoring.getType()) {
                case MANIFEST_SUPPORTS_RTL:
                    performRefactoringForAndroidManifestApplicationTag(refactoring);
                    break;
                case MANIFEST_TARGET_SDK:
                    performRefactoringForAndroidManifestTargetSdk(refactoring);
                    break;
                case LAYOUT_FILE_ATTRIBUTE:
                    performRefactoringForLayoutFile(refactoring);
                    break;
                case UNDEFINED:
                    break;
                default:
                    assert false : refactoring.getType();
            }
        }
    }

    @Override
    protected void performPsiSpoilingRefactoring() {
        PsiDocumentManager.getInstance(myProject).commitAllDocuments();
    }

    private void addManifestRefactoring(List<UsageInfo> list) {
        // For all non library modules in our project
        for (Module module : ModuleManager.getInstance(myProject).getModules()) {
            AndroidFacet facet = AndroidFacet.getInstance(module);
            if (facet != null && !facet.getProperties().LIBRARY_PROJECT) {
                final VirtualFile manifestFile = AndroidRootUtil.getManifestFile(facet);

                XmlFile myManifestFile = (XmlFile)PsiManager.getInstance(myProject).findFile(manifestFile);

                try {
                    XmlTag root = myManifestFile.getRootTag();
                    if (root == null) {
                        return;
                    }

                    // First, deal with "supportsRtl" into the <application> tag
                    XmlTag[] applicationNodes = root.findSubTags(NODE_APPLICATION);
                    if (applicationNodes.length > 0) {
                        assert applicationNodes.length == 1;

                        XmlTag applicationTag = applicationNodes[0];
                        XmlAttribute supportsRtlAttribute = applicationTag.getAttribute(AndroidManifest.ATTRIBUTE_SUPPORTS_RTL, ANDROID_URI);
                        if (supportsRtlAttribute == null || supportsRtlAttribute.getValue().equals(SdkConstants.VALUE_FALSE)) {
                            final int startOffset;
                            final int endOffset;
                            if (supportsRtlAttribute == null) {
                                XmlAttribute[] applicationTagAttributes = applicationTag.getAttributes();
                                XmlAttribute lastAttribute = applicationTagAttributes[applicationTagAttributes.length - 1];
                                PsiElement nextSibling = lastAttribute.getNextSibling();
                                assert nextSibling != null;

                                // Will position the caret just before the ">" for the application tag
                                startOffset = nextSibling.getStartOffsetInParent() + nextSibling.getTextLength();
                                endOffset = startOffset;
                            } else {
                                // Will position the caret at the begining of the "supportsRtl" attribute
                                startOffset = supportsRtlAttribute.getStartOffsetInParent();
                                endOffset = startOffset + supportsRtlAttribute.getTextLength();
                            }

                            RtlRefactoringUsageInfo usageInfo = new RtlRefactoringUsageInfo(applicationTag, startOffset, endOffset);
                            usageInfo.setType(MANIFEST_SUPPORTS_RTL);

                            list.add(usageInfo);
                        }
                    }

                    // Second, deal with targetSdkVersion / minSdkVersion
                    XmlTag[] usesSdkNodes = root.findSubTags(NODE_USES_SDK);
                    if (usesSdkNodes.length > 0) {
                        assert usesSdkNodes.length == 1;

                        XmlTag usesSdkTag = usesSdkNodes[0];
                        XmlAttribute targetSdkAttribute = usesSdkTag.getAttribute(ATTRIBUTE_TARGET_SDK_VERSION, ANDROID_URI);
                        int targetSdk = (targetSdkAttribute != null) ? Integer.parseInt(targetSdkAttribute.getValue()) : 0;

                        // Will need to set existing targetSdkVersion to 17
                        if (targetSdk == 0 || targetSdk < RTL_TARGET_SDK_START) {
                            // Will position the caret just at the start of
                            final int startOffset = (targetSdkAttribute != null)
                                                    ? targetSdkAttribute.getStartOffsetInParent()
                                                    : usesSdkTag.getStartOffsetInParent();
                            final int endOffset = startOffset +
                                                  ((targetSdkAttribute != null)
                                                   ? targetSdkAttribute.getTextLength()
                                                   : usesSdkTag.getTextLength());

                            RtlRefactoringUsageInfo usageInfo = new RtlRefactoringUsageInfo(usesSdkTag, startOffset, endOffset);
                            usageInfo.setType(MANIFEST_TARGET_SDK);

                            list.add(usageInfo);
                        }
                    }
                }
                catch (Exception e) {
                    LOG.error("Could not read Manifest data", e);
                }
            }
        }
    }

    private static String quote(String str) {
        return quoteWith(str, "'");
    }

    private static String quoteWith(String str, String quote) {
        final StringBuilder sb = new StringBuilder(str.length() + 2 * quote.length()).append(quote);
        sb.append(str);
        sb.append(quote);
        return sb.toString();
    }

    @Nullable
    private VirtualFile getLayoutV17(final VirtualFile oneLayoutRes, boolean bCreateIfNeeded) {
        final String resName = oneLayoutRes.getName();
        if (resName.contains(RES_V_QUALIFIER)) {
            return null;
        }
        final String resNameWithV17 = resName + RES_V17_QUALIFIER;
        final VirtualFile parent = oneLayoutRes.getParent();
        assert parent != null;
        VirtualFile layoutV17Dir = parent.findChild(resNameWithV17);

        if ((layoutV17Dir == null || !layoutV17Dir.exists()) && bCreateIfNeeded) {
            try {
                layoutV17Dir = parent.createChildDirectory(null /* external requestor */, resNameWithV17);
            }
            catch (IOException e) {
                LOG.error("Cannot create " + quote(resNameWithV17) + " directory in resource directory: " + parent.getName());
            }
        }

        if (layoutV17Dir != null) {
            assert layoutV17Dir.isDirectory() : layoutV17Dir;
        }
        return layoutV17Dir;
    }

    private List<UsageInfo> getLayoutRefactoringForOneFile(@NotNull VirtualFile layoutFile, boolean createV17) {
        final PsiFile psiFile = PsiManager.getInstance(myProject).findFile(layoutFile);
        assert psiFile != null;
        return getLayoutRefactoringForFile(psiFile, createV17);
    }

    private List<UsageInfo> getLayoutRefactoringForOneDir(@NotNull VirtualFile layoutDir, boolean createV17) {
        List<UsageInfo> result = new ArrayList<UsageInfo>();

        final VirtualFile[] layoutChildren = layoutDir.getChildren();
        for (final VirtualFile oneLayoutFile : layoutChildren) {
            result.addAll(getLayoutRefactoringForOneFile(oneLayoutFile, createV17));
        }

        return result;
    }

    private void addLayoutRefactoring(List<UsageInfo> list) {
        // For all non library modules in our project
        for (Module module : ModuleManager.getInstance(myProject).getModules()) {
            AndroidFacet facet = AndroidFacet.getInstance(module);
            if (facet != null && !facet.getProperties().LIBRARY_PROJECT) {
                if (myProperties.generateV17resourcesOption) {
                    // First get all the "res" directories
                    final List<VirtualFile> allRes = facet.getAllResourceDirectories();

                    // Then, need to get all the "layout-XXX" sub directories
                    final List<VirtualFile> allLayoutDir = new ArrayList<VirtualFile>();

                    for (VirtualFile oneRes : allRes) {
                        final VirtualFile[] children = oneRes.getChildren();
                        // Check every children if they are a layout dir but not a "-v17" one
                        for (VirtualFile oneChild : children) {
                            final String childName = oneChild.getName();
                            if (childName.startsWith(FD_RES_LAYOUT) && !childName.contains(RES_V_QUALIFIER)) {
                                allLayoutDir.add(oneChild);
                            }
                        }
                    }

                    // For all "layout-XXX" entries, process all the contained files
                    for (final VirtualFile layoutDir : allLayoutDir) {
                        final VirtualFile layoutV17Dir = getLayoutV17(layoutDir, false /* no creation */);

                        // The corresponding "v17" directory already exists
                        if (layoutV17Dir != null) {
                            // ... so add refactoring for all files in the "v17" directory if needed
                            if (layoutV17Dir.getChildren().length != 0) {
                                list.addAll(getLayoutRefactoringForOneDir(layoutV17Dir, false /* do not create v17 version */));
                            } else {
                                list.addAll(getLayoutRefactoringForOneDir(layoutDir, true /* create v17 version */));
                            }
                        } else {
                            // otherwise all refactoring for all the non "v17" file and will create the "v17" file later on (we *cannot*
                            // create them here even with a ApplicationManager.getApplication().runWriteAction(...)
                            list.addAll(getLayoutRefactoringForOneDir(layoutDir, true /* create the v17 version */));
                        }
                    }
                } else {
                    final List<PsiFile> files = facet.getLocalResourceManager().findResourceFiles(ResourceFolderType.LAYOUT.getName());

                    for (PsiFile psiFile: files) {
                        list.addAll(getLayoutRefactoringForFile(psiFile, false /* do not create the v17 version */));
                    }
                }
            }
        }
    }

    private List<UsageInfo> getLayoutRefactoringForFile(@NotNull final PsiFile layoutFile, final boolean createV17) {
        final List<UsageInfo> result = new ArrayList<UsageInfo>();

        if (layoutFile instanceof XmlFile &&
            DomManager.getDomManager(myProject).getDomFileDescription((XmlFile) layoutFile) instanceof LayoutDomFileDescription) {
            layoutFile.accept(new XmlRecursiveElementVisitor() {
                @Override
                public void visitXmlTag(XmlTag tag) {
                    super.visitXmlTag(tag);

                    List<UsageInfo> usageInfos = getLayoutRefactoringForTag(tag, createV17);
                    if (usageInfos.isEmpty()) {
                        return;
                    }
                    result.addAll(usageInfos);
                }
            });
        }

        return result;
    }

    private List<UsageInfo> getLayoutRefactoringForTag(@NotNull XmlTag tag, boolean createV17) {
        final DomElement domElement = DomManager.getDomManager(myProject).getDomElement(tag);

        if (!(domElement instanceof LayoutViewElement)) {
            return Collections.emptyList();
        }

        final List<UsageInfo> result = new ArrayList<UsageInfo>();

        final XmlAttribute[] attributes = tag.getAttributes();
        for (XmlAttribute attributeToMirror : attributes) {
            final String localName = attributeToMirror.getLocalName();
            final String namespacePrefix = attributeToMirror.getNamespacePrefix();

            final String mirroredLocalName = ourMapMirroredAttributeName.get(localName);
            // Check if this is a RTL attribute to mirror or if it is a Gravity attribute
            if (mirroredLocalName != null) {
                // Mirror only attributes that has not been mirrored before
                final XmlAttribute attributeMirrored = tag.getAttribute(namespacePrefix + ":" + mirroredLocalName);
                if (attributeMirrored == null) {
                    final int startOffset = 0;
                    final int endOffset = attributeToMirror.getTextLength();
                    RtlRefactoringUsageInfo usageInfoForAttribute = new RtlRefactoringUsageInfo(attributeToMirror, startOffset, endOffset);
                    usageInfoForAttribute.setType(LAYOUT_FILE_ATTRIBUTE);
                    usageInfoForAttribute.setCreateV17(createV17);
                    result.add(usageInfoForAttribute);
                }
            } else if (localName.equals(ATTR_GRAVITY) || localName.equals(ATTR_LAYOUT_GRAVITY)) {
                final int startOffset = 0;
                final int endOffset = attributeToMirror.getTextLength();
                RtlRefactoringUsageInfo usageInfoForAttribute = new RtlRefactoringUsageInfo(attributeToMirror, startOffset, endOffset);
                usageInfoForAttribute.setType(LAYOUT_FILE_ATTRIBUTE);
                usageInfoForAttribute.setCreateV17(createV17);
                result.add(usageInfoForAttribute);
            }
        }

        return result;
    }

    private void performRefactoringForAndroidManifestApplicationTag(@NotNull UsageInfo usageInfo) {
        PsiElement element = usageInfo.getElement();
        assert element != null;
        XmlTag applicationTag = (XmlTag) element;

        XmlAttribute supportsRtlAttribute = applicationTag.getAttribute(ATTRIBUTE_SUPPORTS_RTL, ANDROID_URI);
        if (supportsRtlAttribute != null) {
            supportsRtlAttribute.setValue(SdkConstants.VALUE_TRUE);
        }
        else {
            applicationTag.setAttribute(ATTRIBUTE_SUPPORTS_RTL, ANDROID_URI, SdkConstants.VALUE_TRUE);
        }
    }

    private void performRefactoringForAndroidManifestTargetSdk(@NotNull UsageInfo usageInfo) {
        PsiElement element = usageInfo.getElement();
        assert element != null;
        XmlTag usesSdkTag = (XmlTag) element;

        XmlAttribute targetSdkAttribute = usesSdkTag.getAttribute(ATTRIBUTE_TARGET_SDK_VERSION, ANDROID_URI);
        if (targetSdkAttribute != null) {
            targetSdkAttribute.setValue(Integer.toString(RTL_TARGET_SDK_START));
        }
        else {
            usesSdkTag.setAttribute(ATTRIBUTE_TARGET_SDK_VERSION, ANDROID_URI, Integer.toString(RTL_TARGET_SDK_START));
        }
    }

    private void performRefactoringForLayoutFile(@NotNull RtlRefactoringUsageInfo usageInfo) {
        final PsiElement element = usageInfo.getElement();
        assert element != null;

        final XmlAttribute attribute = (XmlAttribute) element;

        if (!usageInfo.isCreateV17()) {
            updateAttributeForElement(attribute);
        } else {
            // We need first to create the v17 layout file, so first get our initial layout file
            final PsiFile psiFile = element.getContainingFile();

            final VirtualFile layoutFile = psiFile.getVirtualFile();
            assert layoutFile != null;

            final VirtualFile layoutDir = layoutFile.getParent();
            assert layoutDir != null;

            final VirtualFile layoutV17Dir = getLayoutV17(layoutDir, true /* create if needed */);
            assert layoutV17Dir != null;

            final String layoutFileName = layoutFile.getName();

            // Create the v17 file if needed (should be done only once)
            if (layoutV17Dir.findChild(layoutFileName) == null) {
                ApplicationManager.getApplication().runWriteAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            layoutFile.copy(null /* external requestor */, layoutV17Dir, layoutFileName);
                        }
                        catch (IOException e) {
                            LOG.error("Cannot copy layout file " + quote(layoutFileName) + " from " +
                                      quote(layoutDir.getName()) + " directory to " + quote(layoutV17Dir.getName()) +
                                      " directory");
                        }
                    }
                });
            }

            final VirtualFile layoutV17File = layoutV17Dir.findChild(layoutFileName);
            assert layoutV17File != null;

            final XmlFile xmlV17File = (XmlFile) PsiManager.getInstance(myProject).findFile(layoutV17File);
            assert xmlV17File != null;

            LOG.info("Processing refactoring for attribute: " + attribute.getName() + " into file: " + layoutV17File.getPath());

            if (DomManager.getDomManager(myProject).getDomFileDescription((XmlFile) xmlV17File) instanceof LayoutDomFileDescription) {
                xmlV17File.accept(new XmlRecursiveElementVisitor() {
                    @Override
                    public void visitXmlTag(XmlTag tag) {
                        super.visitXmlTag(tag);

                        final XmlAttribute attribute = tag.getAttribute(((XmlAttribute)element).getName());
                        if (attribute == null) {
                            return;
                        }
                        updateAttributeForElement(attribute);
                    }
                });
            }

            layoutV17File.refresh(true /* asynchronous */, false /* not recursive */);
        }
    }

    private void updateAttributeForElement(@NotNull XmlAttribute attribute) {
        final String attributeLocalName = attribute.getLocalName();
        LOG.info("Updating attribute name: " + attributeLocalName + " value: " + attribute.getValue());

        if (attributeLocalName.equals(ATTR_GRAVITY) || attributeLocalName.equals(ATTR_LAYOUT_GRAVITY)) {
            // Special case for android:gravity and android:layout_gravity
            final String value = attribute.getValue();
            final String newValue = value.replace(GRAVITY_VALUE_LEFT, GRAVITY_VALUE_START).replace(GRAVITY_VALUE_RIGHT, GRAVITY_VALUE_END);
            attribute.setValue(newValue);
            LOG.info("Changing gravity from: " + value + " to: " + newValue);
        } else {
            // General case for RTL attributes
            final String mirroredAttributeLocalName = ourMapMirroredAttributeName.get(attributeLocalName);
            if (mirroredAttributeLocalName == null) {
                LOG.warn("Cannot mirror attribute: " + attribute.toString());
                return;
            }
            final String mirroredAttributeName = attribute.getNamespacePrefix() + ":" + mirroredAttributeLocalName;
            if(myProperties.replaceLeftRightPropertiesOption) {
                attribute.setName(mirroredAttributeName);
                LOG.info("Replacing attribute name from: " + attributeLocalName + " to: " + mirroredAttributeLocalName);
            } else {
                XmlTag parent = attribute.getParent();
                parent.setAttribute(mirroredAttributeName, attribute.getValue());
                LOG.info("Adding attribute name: " + mirroredAttributeName + " value: " + attribute.getValue());
            }
        }
    }

    @Override
    protected String getCommandName() {
        return REFACTORING_NAME;
    }

}