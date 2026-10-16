import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 开发工具（不参与构建）：从《杀戮尖塔》的 desktop-1.0.jar 里读出每个怪物类的
 * ID 常量与全部「static final + ConstantValue」字段，用来核对本项目的怪物表。
 *
 * 用法：
 *   javac -encoding UTF-8 -d <out> tools/inspect/ExtractMonsterFacts.java
 *   java -cp <out> ExtractMonsterFacts "C:/.../desktop-1.0.jar"
 *
 * 输出每行：类路径 | ID=xxx | 若干 name=value。
 * 该工具只读取游戏 jar，不修改任何文件。
 */
public final class ExtractMonsterFacts {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ExtractMonsterFacts <desktop-1.0.jar>");
            System.exit(2);
        }
        ZipFile zip = new ZipFile(new File(args[0]));
        try {
            List<String> names = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("com/megacrit/cardcrawl/monsters/")
                        && n.endsWith(".class") && n.indexOf('$') < 0) {
                    names.add(n);
                }
            }
            Collections.sort(names);
            StringBuilder out = new StringBuilder();
            for (String n : names) {
                byte[] bytes = readAll(zip.getInputStream(zip.getEntry(n)));
                try {
                    out.append(describe(n, bytes));
                } catch (Throwable t) {
                    out.append(n).append(" | PARSE_ERROR ").append(t);
                }
                out.append('\n');
            }
            System.out.print(out);
        } finally {
            zip.close();
        }
    }

    private static String describe(String entry, byte[] b) {
        Reader r = new Reader(b);
        if (r.u4() != 0xCAFEBABEL) {
            throw new IllegalStateException("bad magic");
        }
        r.u2(); // minor
        r.u2(); // major
        int cpCount = r.u2();
        Object[] cp = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = r.u1();
            switch (tag) {
                case 1: // Utf8
                    cp[i] = r.utf8();
                    break;
                case 3: // Integer
                    cp[i] = Integer.valueOf((int) r.u4());
                    break;
                case 4: // Float
                    cp[i] = Float.valueOf(Float.intBitsToFloat((int) r.u4()));
                    break;
                case 5: // Long
                    cp[i] = Long.valueOf(r.u8());
                    i++;
                    break;
                case 6: // Double
                    cp[i] = Double.valueOf(Double.longBitsToDouble(r.u8()));
                    i++;
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    cp[i] = new Ref(r.u2());
                    break;
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    r.u2();
                    r.u2();
                    break;
                case 15: // MethodHandle
                    r.u1();
                    r.u2();
                    break;
                default:
                    throw new IllegalStateException("bad cp tag " + tag);
            }
        }
        // String/Class 常量引用 Utf8：解析一次即可（Utf8 不再引用别的项）。
        for (int i = 1; i < cpCount; i++) {
            if (cp[i] instanceof Ref) {
                Object target = cp[((Ref) cp[i]).idx];
                if (target instanceof String) {
                    cp[i] = target;
                }
            }
        }
        r.u2(); // access flags
        int thisClass = r.u2();
        r.u2(); // super
        int ifCount = r.u2();
        for (int i = 0; i < ifCount; i++) {
            r.u2();
        }

        StringBuilder id = new StringBuilder();
        StringBuilder nums = new StringBuilder();
        int fieldCount = r.u2();
        for (int i = 0; i < fieldCount; i++) {
            int access = r.u2();
            String name = str(cp, r.u2());
            String desc = str(cp, r.u2());
            int attrCount = r.u2();
            for (int a = 0; a < attrCount; a++) {
                String aname = str(cp, r.u2());
                int len = (int) r.u4();
                if ("ConstantValue".equals(aname)) {
                    int idx = r.u2();
                    r.skip(len - 2);
                    Object v = cp[idx];
                    if ("ID".equals(name) && v != null) {
                        id.append(v);
                    } else if ((access & 0x0008) != 0 && v != null) {
                        if (nums.length() > 0) {
                            nums.append(' ');
                        }
                        nums.append(name).append('=').append(fmt(v)).append('[').append(desc).append(']');
                    }
                } else {
                    r.skip(len);
                }
            }
        }

        String simple = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - ".class".length());
        String pkg = entry.substring(0, entry.lastIndexOf('/'));
        pkg = pkg.substring(pkg.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(pkg).append('/').append(simple).append(" | ID=").append(id);
        if (nums.length() > 0) {
            sb.append(" | ").append(nums);
        }
        return sb.toString();
    }

    private static String fmt(Object v) {
        if (v instanceof Float) {
            float f = ((Float) v).floatValue();
            if (f == Math.rint(f) && !Float.isInfinite(f)) {
                return String.valueOf((int) f);
            }
            return String.valueOf(f);
        }
        return String.valueOf(v);
    }

    private static final class Ref {
        final int idx;

        Ref(int idx) {
            this.idx = idx;
        }
    }

    private static String str(Object[] cp, int idx) {
        Object o = idx >= 0 && idx < cp.length ? cp[idx] : null;
        return o instanceof String ? (String) o : "?";
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static final class Reader {
        private final byte[] b;
        private int p;

        Reader(byte[] b) {
            this.b = b;
        }

        int u1() {
            return b[p++] & 0xFF;
        }

        int u2() {
            return ((b[p++] & 0xFF) << 8) | (b[p++] & 0xFF);
        }

        long u4() {
            return ((long) (u2()) << 16) | (u2() & 0xFFFFL);
        }

        long u8() {
            return (u4() << 32) | (u4() & 0xFFFFFFFFL);
        }

        String utf8() {
            int len = u2();
            String s = new String(b, p, len, java.nio.charset.StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        void skip(int n) {
            p += n;
        }
    }
}
