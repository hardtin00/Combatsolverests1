import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 开发工具：把游戏 jar 里某个方法的字节码打印出来（十六进制 + 常用指令解码）。
 * 用来确认游戏内部实现细节，例如 CardGroup.getTopCard() 到底取列表的哪一端。
 *
 * 用法：
 *   java -cp <out> DumpBytecode <desktop-1.0.jar> <com/x/Y> <methodName>
 */
public final class DumpBytecode {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: DumpBytecode <jar> <internalClassName> <methodName>");
            System.exit(2);
        }
        ZipFile zip = new ZipFile(new File(args[0]));
        try {
            ZipEntry entry = zip.getEntry(args[1].replace('.', '/') + ".class");
            if (entry == null) {
                System.err.println("class not found: " + args[1]);
                System.exit(3);
            }
            byte[] bytes = readAll(zip.getInputStream(entry));
            dump(bytes, args[2]);
        } finally {
            zip.close();
        }
    }

    private static void dump(byte[] b, String methodName) {
        Reader r = new Reader(b);
        r.u4();
        r.u2();
        r.u2();
        int cpCount = r.u2();
        String[] utf8 = new String[cpCount];
        int[] stringRef = new int[cpCount];
        int[] classNameRef = new int[cpCount];
        int[] nameAndTypeName = new int[cpCount];
        int[] nameAndTypeDesc = new int[cpCount];
        int[] refClass = new int[cpCount];
        int[] refNameType = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = r.u1();
            switch (tag) {
                case 1: utf8[i] = r.utf8(); break;
                case 3: case 4: r.u4(); break;
                case 5: case 6: r.u8(); i++; break;
                case 7: classNameRef[i] = r.u2(); break;
                case 8: stringRef[i] = r.u2(); break;
                case 9: case 10: case 11: refClass[i] = r.u2(); refNameType[i] = r.u2(); break;
                case 12: nameAndTypeName[i] = r.u2(); nameAndTypeDesc[i] = r.u2(); break;
                case 15: r.u1(); r.u2(); break;
                case 16: case 19: case 20: r.u2(); break;
                case 17: case 18: r.u2(); r.u2(); break;
                default: throw new IllegalStateException("cp tag " + tag);
            }
        }
        r.u2();
        r.u2();
        r.u2();
        int ifCount = r.u2();
        for (int i = 0; i < ifCount; i++) {
            r.u2();
        }
        int fieldCount = r.u2();
        for (int i = 0; i < fieldCount; i++) {
            r.u2();
            r.u2();
            r.u2();
            int attrs = r.u2();
            for (int a = 0; a < attrs; a++) {
                r.u2();
                int len = (int) r.u4();
                r.skip(len);
            }
        }
        int methodCount = r.u2();
        for (int i = 0; i < methodCount; i++) {
            r.u2();
            String name = utf8[r.u2()];
            String desc = utf8[r.u2()];
            int attrs = r.u2();
            for (int a = 0; a < attrs; a++) {
                String attrName = utf8[r.u2()];
                int len = (int) r.u4();
                if ("Code".equals(attrName)) {
                    byte[] code = decode(r, methodName, name, desc, utf8, stringRef == null
                            ? null : stringRef, cpCount);
                    if (code != null && name.equals(methodName)) {
                        System.out.println("== " + name + desc + " ==");
                        System.out.println(toHex(code));
                        System.out.println(disassemble(code));
                    } else {
                        r.skip(len);
                    }
                } else {
                    r.skip(len);
                }
            }
        }
    }

    /** 读取 Code 属性并返回字节码；如果方法与目标不符则返回 null（调用方负责跳过）。 */
    private static byte[] decode(Reader r, String target, String name, String desc,
                                 String[] utf8, int[] stringRef, int cpCount) {
        if (!name.equals(target)) {
            return null;
        }
        r.u2();
        r.u2();
        int codeLen = (int) r.u4();
        byte[] code = r.bytes(codeLen);
        int exCount = r.u2();
        r.skip(exCount * 8);
        int attrs = r.u2();
        for (int i = 0; i < attrs; i++) {
            r.u2();
            int len = (int) r.u4();
            r.skip(len);
        }
        return code;
    }

    private static String toHex(byte[] code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", code[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String disassemble(byte[] code) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < code.length) {
            int op = code[i] & 0xFF;
            String line = "  " + i + ": " + name(op);
            if (op == 0x10) { // bipush
                line += " " + code[i + 1];
                i += 2;
            } else if (op == 0x11) { // sipush
                line += " " + ((code[i + 1] << 8) | (code[i + 2] & 0xFF));
                i += 3;
            } else if (op == 0xB2 || op == 0xB3 || op == 0xB4 || op == 0xB5 || op == 0xB6
                    || op == 0xB7 || op == 0xB8 || op == 0x12 || op == 0xBB || op == 0xBD
                    || op == 0xC0 || op == 0xC1) {
                line += " #" + (((code[i + 1] & 0xFF) << 8) | (code[i + 2] & 0xFF));
                i += 3;
            } else if (op == 0xB9 || op == 0xBA || op == 0xC8 || op == 0xC9) {
                i += 5;
            } else if (op == 0x84) {
                i += 3;
            } else {
                i += 1;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String name(int op) {
        switch (op) {
            case 0x00: return "nop";
            case 0x01: return "aconst_null";
            case 0x02: return "iconst_m1";
            case 0x03: return "iconst_0";
            case 0x04: return "iconst_1";
            case 0x05: return "iconst_2";
            case 0x06: return "iconst_3";
            case 0x07: return "iconst_4";
            case 0x08: return "iconst_5";
            case 0x10: return "bipush";
            case 0x11: return "sipush";
            case 0x12: return "ldc";
            case 0x15: return "iload";
            case 0x19: return "aload";
            case 0x1a: return "iload_0";
            case 0x1b: return "iload_1";
            case 0x2a: return "aload_0";
            case 0x2b: return "aload_1";
            case 0x2c: return "aload_2";
            case 0x3b: return "istore_0";
            case 0x4b: return "astore_0";
            case 0x57: return "pop";
            case 0x59: return "dup";
            case 0x60: return "iadd";
            case 0x64: return "isub";
            case 0x68: return "imul";
            case 0x6c: return "idiv";
            case 0x84: return "iinc";
            case 0x99: return "ifeq";
            case 0x9a: return "ifne";
            case 0x9e: return "ifle";
            case 0xa7: return "goto";
            case 0xac: return "ireturn";
            case 0xb0: return "areturn";
            case 0xb1: return "return";
            case 0xb2: return "getstatic";
            case 0xb4: return "getfield";
            case 0xb5: return "putfield";
            case 0xb6: return "invokevirtual";
            case 0xb7: return "invokespecial";
            case 0xb8: return "invokestatic";
            case 0xb9: return "invokeinterface";
            case 0xbb: return "new";
            case 0xbd: return "anewarray";
            case 0xc0: return "checkcast";
            default: return String.format("op_%02x", op);
        }
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

        byte[] bytes(int len) {
            byte[] out = new byte[len];
            System.arraycopy(b, p, out, 0, len);
            p += len;
            return out;
        }

        void skip(int n) {
            p += n;
        }
    }
}
