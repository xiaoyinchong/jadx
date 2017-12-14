package jadx.core.deobf;

import jadx.api.IJadxArgs;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.SourceFileAttr;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.DexNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.utils.NameFactory;
import jadx.core.utils.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class Deobfuscator {
    private static final Logger LOG = LoggerFactory.getLogger(Deobfuscator.class);

    private static final boolean DEBUG = false;

    public static final String CLASS_NAME_SEPARATOR = ".";
    public static final String INNER_CLASS_SEPARATOR = "$";

    private final IJadxArgs args;
    @NotNull
    private final List<DexNode> dexNodes;
    private final DeobfPresets deobfPresets;

    private final Map<ClassInfo, DeobfClsInfo> clsMap = new HashMap<ClassInfo, DeobfClsInfo>();
    private final Map<FieldInfo, String> fldMap = new HashMap<FieldInfo, String>();
    private final Map<MethodInfo, String> mthMap = new HashMap<MethodInfo, String>();

    private final Map<MethodInfo, OverridedMethodsNode> ovrdMap = new HashMap<MethodInfo, OverridedMethodsNode>();
    private final List<OverridedMethodsNode> ovrd = new ArrayList<OverridedMethodsNode>();

    private final PackageNode rootPackage = new PackageNode("");
    private final Set<String> pkgSet = new TreeSet<String>();

    private final int maxLength;
    private final int minLength;
    private final boolean useSourceNameAsAlias;

    public Deobfuscator(IJadxArgs args, @NotNull List<DexNode> dexNodes, File deobfMapFile) {
        this.args = args;
        this.dexNodes = dexNodes;

        this.minLength = args.getDeobfuscationMinLength();
        this.maxLength = args.getDeobfuscationMaxLength();
        this.useSourceNameAsAlias = args.useSourceNameAsClassAlias();

        this.deobfPresets = new DeobfPresets(this, deobfMapFile);
        NameFactory.reset();
    }

    public void execute() {
        if (!args.isDeobfuscationForceSave()) {
            deobfPresets.load();
        }
        process();
        deobfPresets.save(args.isDeobfuscationForceSave());
        clear();
    }

    private void preProcess() {
        for (DexNode dexNode : dexNodes) {
            for (ClassNode cls : dexNode.getClasses()) {
                doClass(cls);
            }
        }
    }

    private void process() {
        preProcess();
        if (DEBUG) {
            dumpAlias();
        }
        for (DexNode dexNode : dexNodes) {
            for (ClassNode cls : dexNode.getClasses()) {
                processClass(dexNode, cls);
            }
        }
        postProcess();
    }

    private void postProcess() {
        for (OverridedMethodsNode o : ovrd) {

            Iterator<MethodInfo> it = o.getMethods().iterator();
            if (it.hasNext()) {
                MethodInfo mth = it.next();

                if (mth.isRenamed() && !mth.isAliasFromPreset()) {
                    mth.setAlias(NameFactory.nextName());
                }
                String firstMethodAlias = mth.getAlias();

                while (it.hasNext()) {
                    mth = it.next();
                    if (!mth.getAlias().equals(firstMethodAlias)) {
                        mth.setAlias(firstMethodAlias);
                    }
                }
            }
        }
    }

    void clear() {
        deobfPresets.clear();
        clsMap.clear();
        fldMap.clear();
        mthMap.clear();

        ovrd.clear();
        ovrdMap.clear();
    }

    @Nullable
    private static ClassNode resolveOverridingInternal(DexNode dex, ClassNode cls, String signature,
                                                       Set<MethodInfo> overrideSet, ClassNode rootClass) {
        ClassNode result = null;

        for (MethodNode m : cls.getMethods()) {
            if (m.getMethodInfo().getShortId().startsWith(signature)) {
                result = cls;
                if (!overrideSet.contains(m.getMethodInfo())) {
                    overrideSet.add(m.getMethodInfo());
                }
                break;
            }
        }

        ArgType superClass = cls.getSuperClass();
        if (superClass != null) {
            ClassNode superNode = dex.resolveClass(superClass);
            if (superNode != null) {
                ClassNode clsWithMth = resolveOverridingInternal(dex, superNode, signature, overrideSet, rootClass);
                if (clsWithMth != null) {
                    if ((result != null) && (result != cls)) {
                        if (clsWithMth != result) {
                            LOG.warn(String.format("Multiple overriding '%s' from '%s' and '%s' in '%s'",
                                    signature,
                                    result.getFullName(), clsWithMth.getFullName(),
                                    rootClass.getFullName()));
                        }
                    } else {
                        result = clsWithMth;
                    }
                }
            }
        }

        for (ArgType iFaceType : cls.getInterfaces()) {
            ClassNode iFaceNode = dex.resolveClass(iFaceType);
            if (iFaceNode != null) {
                ClassNode clsWithMth = resolveOverridingInternal(dex, iFaceNode, signature, overrideSet, rootClass);
                if (clsWithMth != null) {
                    if ((result != null) && (result != cls)) {
                        if (clsWithMth != result) {
                            LOG.warn(String.format("Multiple overriding '%s' from '%s' and '%s' in '%s'",
                                    signature,
                                    result.getFullName(), clsWithMth.getFullName(),
                                    rootClass.getFullName()));
                        }
                    } else {
                        result = clsWithMth;
                    }
                }
            }
        }

        return result;
    }

    private void resolveOverriding(DexNode dex, ClassNode cls, MethodNode mth) {
        Set<MethodInfo> overrideSet = new HashSet<MethodInfo>();
        resolveOverridingInternal(dex, cls, mth.getMethodInfo().makeSignature(false), overrideSet, cls);

        if (overrideSet.size() > 1) {
            OverridedMethodsNode overrideNode = null;
            for (MethodInfo _mth : overrideSet) {
                if (ovrdMap.containsKey(_mth)) {
                    overrideNode = ovrdMap.get(_mth);
                    break;
                }
            }

            if (overrideNode == null) {
                overrideNode = new OverridedMethodsNode(overrideSet);
                ovrd.add(overrideNode);
            }

            for (MethodInfo _mth : overrideSet) {
                if (!ovrdMap.containsKey(_mth)) {
                    ovrdMap.put(_mth, overrideNode);
                    if (!overrideNode.contains(_mth)) {
                        overrideNode.add(_mth);
                    }
                }
            }
        } else {
            overrideSet.clear();
            overrideSet = null;
        }
    }

    private void processClass(DexNode dex, ClassNode cls) {
        ClassInfo clsInfo = cls.getClassInfo();
        String fullName = getClassFullName(clsInfo);
        if (!fullName.equals(clsInfo.getFullName()) && !StringUtils.isContainNumber(fullName)) {
            clsInfo.rename(dex, fullName);
        }
        for (FieldNode field : cls.getFields()) {
            FieldInfo fieldInfo = field.getFieldInfo();
            String alias = getFieldAlias(field);
            if (alias != null && !StringUtils.isContainNumber(alias)) {
                fieldInfo.setAlias(alias);
            }
        }
        for (MethodNode mth : cls.getMethods()) {
            MethodInfo methodInfo = mth.getMethodInfo();
            String alias = getMethodAlias(mth);
            if (alias != null && !StringUtils.isContainNumber(alias)) {
                methodInfo.setAlias(alias);
            }

            if (mth.isVirtual()) {
                resolveOverriding(dex, cls, mth);
            }
        }
    }

    public void addPackagePreset(String origPkgName, String pkgAlias) {
        PackageNode pkg = getPackageNode(origPkgName, true);
        pkg.setAlias(pkgAlias);
    }

    /**
     * Gets package node for full package name
     *
     * @param fullPkgName full package name
     * @param create      if {@code true} then will create all absent objects
     * @return package node object or {@code null} if no package found and <b>create</b> set to {@code false}
     */
    private PackageNode getPackageNode(String fullPkgName, boolean create) {
        if (fullPkgName.isEmpty() || fullPkgName.equals(CLASS_NAME_SEPARATOR)) {
            return rootPackage;
        }
        PackageNode result = rootPackage;
        PackageNode parentNode;
        do {
            String pkgName;
            int idx = fullPkgName.indexOf(CLASS_NAME_SEPARATOR);

            if (idx > -1) {
                pkgName = fullPkgName.substring(0, idx);
                fullPkgName = fullPkgName.substring(idx + 1);
            } else {
                pkgName = fullPkgName;
                fullPkgName = "";
            }
            parentNode = result;
            result = result.getInnerPackageByName(pkgName);
            if (result == null && create) {
                result = new PackageNode(pkgName);
                parentNode.addInnerPackage(result);
            }
        } while (!fullPkgName.isEmpty() && result != null);

        return result;
    }

    String getNameWithoutPackage(ClassInfo clsInfo) {
        String prefix;
        ClassInfo parentClsInfo = clsInfo.getParentClass();
        if (parentClsInfo != null) {
            DeobfClsInfo parentDeobfClsInfo = clsMap.get(parentClsInfo);
            if (parentDeobfClsInfo != null) {
                prefix = parentDeobfClsInfo.makeNameWithoutPkg();
            } else {
                prefix = getNameWithoutPackage(parentClsInfo);
            }
            prefix += INNER_CLASS_SEPARATOR;
        } else {
            prefix = "";
        }
        return prefix + clsInfo.getShortName();
    }

    private void doClass(ClassNode cls) {
        ClassInfo classInfo = cls.getClassInfo();
        String pkgFullName = classInfo.getPackage();
        PackageNode pkg = getPackageNode(pkgFullName, true);
        doPkg(pkg, pkgFullName);

        String alias = deobfPresets.getForCls(classInfo);
        if (alias != null) {
            clsMap.put(classInfo, new DeobfClsInfo(this, cls, pkg, alias));
            return;
        }
        if (clsMap.containsKey(classInfo)) {
            return;
        }
        if (shouldRename(classInfo.getShortName())) {
            makeClsAlias(cls);
        }
    }

    public String getClsAlias(ClassNode cls) {
        DeobfClsInfo deobfClsInfo = clsMap.get(cls.getClassInfo());
        if (deobfClsInfo != null) {
            return deobfClsInfo.getAlias();
        }
        return makeClsAlias(cls);
    }

    private String makeClsAlias(ClassNode cls) {
        ClassInfo classInfo = cls.getClassInfo();
        String alias = null;

        if (this.useSourceNameAsAlias) {
            alias = getAliasFromSourceFile(cls);
        }

        if (alias == null) {
            String clsName = classInfo.getShortName();
            if (clsName.isEmpty() || Character.isDigit(clsName.charAt(0)) || StringUtils.deobfuseName(clsName)) {
                alias = NameFactory.nextName();
            } else {
                alias = clsName;
            }
        }
        PackageNode pkg = getPackageNode(classInfo.getPackage(), true);
        clsMap.put(classInfo, new DeobfClsInfo(this, cls, pkg, alias));
        return alias;
    }

    @Nullable
    private String getAliasFromSourceFile(ClassNode cls) {
        SourceFileAttr sourceFileAttr = cls.get(AType.SOURCE_FILE);
        if (sourceFileAttr == null) {
            return null;
        }
        String name = sourceFileAttr.getFileName();
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - ".java".length());
        }
        if (NameMapper.isValidIdentifier(name)
                && !NameMapper.isReserved(name)) {
            // TODO: check if no class with this name exists or already renamed
            cls.remove(AType.SOURCE_FILE);
            return name;
        }
        return null;
    }

    @Nullable
    public String getFieldAlias(FieldNode field) {
        FieldInfo fieldInfo = field.getFieldInfo();
        String alias = fldMap.get(fieldInfo);
        if (alias != null) {
            return alias;
        }
        alias = deobfPresets.getForFld(fieldInfo);
        if (alias != null) {
            fldMap.put(fieldInfo, alias);
            return alias;
        }
        if (shouldRename(field.getName())) {
            return makeFieldAlias(field);
        }
        return null;
    }

    @Nullable
    public String getMethodAlias(MethodNode mth) {
        MethodInfo methodInfo = mth.getMethodInfo();
        String alias = mthMap.get(methodInfo);
        if (alias != null) {
            return alias;
        }
        alias = deobfPresets.getForMth(methodInfo);
        if (alias != null) {
            mthMap.put(methodInfo, alias);
            methodInfo.setAliasFromPreset(true);
            return alias;
        }
        if (shouldRename(mth.getName())) {
            return makeMethodAlias(mth);
        }
        return null;
    }

    public String makeFieldAlias(FieldNode field) {
        String alias = NameFactory.nextName();
        fldMap.put(field.getFieldInfo(), alias);
        return alias;
    }

    public String makeMethodAlias(MethodNode mth) {
        String alias = NameFactory.nextName();
        mthMap.put(mth.getMethodInfo(), alias);
        return alias;
    }

    private void doPkg(PackageNode pkg, String fullName) {
        if (pkgSet.contains(fullName)) {
            return;
        }
        pkgSet.add(fullName);
//        if (fullName == null || "".equals(fullName) || !shouldRename(fullName.replace(".", ""))) {
//            return;
//        }

        // doPkg for all parent packages except root that not hasAliases
        PackageNode parentPkg = pkg.getParentPackage();
        while (!parentPkg.getName().isEmpty()) {
            if (!parentPkg.hasAlias()) {
                doPkg(parentPkg, parentPkg.getFullName());
            }
            parentPkg = parentPkg.getParentPackage();
        }

        final String pkgName = pkg.getName();
        if (!pkg.hasAlias() && shouldRename(pkgName)) {
            final String pkgAlias = NameFactory.nextName();
            pkg.setAlias(pkgAlias);
        }
    }

    private boolean shouldRename(String s) {
        return s.length() > maxLength
                || s.length() < minLength
                || NameMapper.isReserved(s)
                || !NameMapper.isAllCharsPrintable(s) || StringUtils.deobfuseName(s);
    }

    private String makeName(String name) {
        if (name.length() > maxLength) {
            return "x" + Integer.toHexString(name.hashCode());
        }
        if (NameMapper.isReserved(name)) {
            return name;
        }
        if (!NameMapper.isAllCharsPrintable(name)) {
            return removeInvalidChars(name);
        }
        return name;
    }

    private String removeInvalidChars(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            int ch = name.charAt(i);
            if (NameMapper.isPrintableChar(ch)) {
                sb.append((char) ch);
            }
        }
        return sb.toString();
    }

    private void dumpClassAlias(ClassNode cls) {
        PackageNode pkg = getPackageNode(cls.getPackage(), false);

        if (pkg != null) {
            if (!cls.getFullName().equals(getClassFullName(cls))) {
                LOG.info("Alias name for class '{}' is '{}'", cls.getFullName(), getClassFullName(cls));
            }
        } else {
            LOG.error("Can't find package node for '{}'", cls.getPackage());
        }
    }

    private void dumpAlias() {
        for (DexNode dexNode : dexNodes) {
            for (ClassNode cls : dexNode.getClasses()) {
                dumpClassAlias(cls);
            }
        }
    }

    private String getPackageName(String packageName) {
        final PackageNode pkg = getPackageNode(packageName, false);
        if (pkg != null) {
            return pkg.getFullAlias();
        }
        return packageName;
    }

    private String getClassName(ClassInfo clsInfo) {
        final DeobfClsInfo deobfClsInfo = clsMap.get(clsInfo);
        if (deobfClsInfo != null) {
            return deobfClsInfo.makeNameWithoutPkg();
        }
        return getNameWithoutPackage(clsInfo);
    }

    private String getClassFullName(ClassNode cls) {
        return getClassFullName(cls.getClassInfo());
    }

    private String getClassFullName(ClassInfo clsInfo) {
        final DeobfClsInfo deobfClsInfo = clsMap.get(clsInfo);
        if (deobfClsInfo != null) {
            return deobfClsInfo.getFullName();
        }
        return getPackageName(clsInfo.getPackage()) + CLASS_NAME_SEPARATOR + getClassName(clsInfo);
    }

    public Map<ClassInfo, DeobfClsInfo> getClsMap() {
        return clsMap;
    }

    public Map<FieldInfo, String> getFldMap() {
        return fldMap;
    }

    public Map<MethodInfo, String> getMthMap() {
        return mthMap;
    }

    public PackageNode getRootPackage() {
        return rootPackage;
    }
}
