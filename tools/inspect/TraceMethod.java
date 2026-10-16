import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 开发工具：按顺序打印游戏 jar 里某个方法的「常量与调用轨迹」，
 * 用来还原怪物的出招逻辑（getMove / takeTurn 里做了哪些判断、按什么顺序 setMove）。
 *
 * 输出每行形如：
 *   偏移 指令 解析后的常量/字段/方法
 * 字符串常量直接显示内容，整数常量直接显示数值，引用显示 owner.name。
 *
 * 用法：
 *   java -cp <out> TraceMethod <desktop-1.0.jar> <com/x/Y> <methodName>
 */
public final class TraceMethod {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: TraceMethod <jar> <internalClassName> <methodName>");
            System.exit(2);
        }
        ZipFile zip = new ZipFile(new File(args[0]));
        try {
            ZipEntry entry = zip.getEntry(args[1].replace('.', '/') + ".class");
            if (entry == null) {
                System.err.println("class not found: " + args[1]);
                System.exit(3);
            }
            trace(readAll(zip.getInputStream(entry)), args[2]);
        } finally {
            zip.close();
        }
    }

    private static void trace(byte[] b, String methodName) {
        Reader r = new Reader(b);
        r.u4();
        r.u2();
        r.u2();
        int cpCount = r.u2();
        Object[] cp = new Object[cpCount];
        int[] refIndex = new int[cpCount];
        int[] memberClass = new int[cpCount];
        int[] memberNameType = new int[cpCount];
        int[] ntName = new int[cpCount];
        int[] ntDesc = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = r.u1();
            switch (tag) {
                case 1: cp[i] = r.utf8(); break;
                case 3: cp[i] = Integer.valueOf((int) r.u4()); break;
                case 4: cp[i] = Float.valueOf(Float.intBitsToFloat((int) r.u4())); break;
                case 5: cp[i] = Long.valueOf(r.u8()); i++; break;
                case 6: cp[i] = Double.valueOf(Double.longBitsToDouble(r.u8())); i++; break;
                case 7: case 8: case 16: case 19: case 20: refIndex[i] = r.u2(); break;
                case 9: case 10: case 11:
                    memberClass[i] = r.u2();
                    memberNameType[i] = r.u2();
                    break;
                case 12:
                    ntName[i] = r.u2();
                    ntDesc[i] = r.u2();
                    break;
                case 15: r.u1(); r.u2(); break;
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
                r.skip((int) r.u4());
            }
        }
        int methodCount = r.u2();
        for (int i = 0; i < methodCount; i++) {
            r.u2();
            String name = utf8(cp, r.u2());
            String desc = utf8(cp, r.u2());
            int attrs = r.u2();
            for (int a = 0; a < attrs; a++) {
                String attrName = utf8(cp, r.u2());
                int len = (int) r.u4();
                if ("Code".equals(attrName) && name.equals(methodName)) {
                    r.u2();
                    r.u2();
                    int codeLen = (int) r.u4();
                    byte[] code = r.bytes(codeLen);
                    printTrace(name, desc, code, cp, refIndex, memberClass, memberNameType,
                            ntName, ntDesc);
                    r.skip(len - 8 - codeLen);
                } else {
                    r.skip(len);
                }
            }
        }
    }

    private static void printTrace(String name, String desc, byte[] code, Object[] cp,
                                   int[] refIndex, int[] memberClass, int[] memberNameType,
                                   int[] ntName, int[] ntDesc) {
        System.out.println("== " + name + desc + " ==");
        int p = 0;
        while (p < code.length) {
            int start = p;
            int op = code[p++] & 0xFF;
            StringBuilder line = new StringBuilder();
            line.append(String.format("%5d  %-16s", start, opName(op)));
            int n = operands(op);
            if (op == 0x12 && n >= 1) {
                line.append(resolveConstant(cp, refIndex, code[p] & 0xFF));
                p += 1;
            } else if ((op == 0x13 || op == 0x14) && n >= 2) {
                int idx = u2(code, p);
                line.append(resolveConstant(cp, refIndex, idx));
                p += 2;
            } else if (op == 0x10) {
                line.append((int) code[p]);
                p += 1;
            } else if (op == 0x11) {
                line.append((short) u2(code, p));
                p += 2;
            } else if (op >= 0x02 && op <= 0x08) {
                line.append(op - 0x03);
            } else if (op >= 0xb2 && op <= 0xb8) {
                int idx = u2(code, p);
                line.append(member(cp, refIndex, memberClass, memberNameType, ntName, ntDesc, idx));
                p += 2;
            } else if ((op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3a) || op == 0xa9) {
                line.append(code[p] & 0xFF);
                p += 1;
            } else if ((op >= 0x99 && op <= 0xa7) || op == 0xc6 || op == 0xc7) {
                int target = start + (short) u2(code, p);
                line.append("-> ").append(target);
                p += 2;
            } else if (op == 0xb9 || op == 0xba) {
                int idx = u2(code, p);
                line.append(member(cp, refIndex, memberClass, memberNameType, ntName, ntDesc, idx));
                p += 4;
            } else if (op == 0xaa) {
                // tableswitch：pad / default / low / high / 偏移表
                p += (4 - (p % 4)) % 4;
                int def = (int) u4(code, p);
                p += 4;
                int low = (int) u4(code, p);
                p += 4;
                int high = (int) u4(code, p);
                p += 4;
                line.append("default->").append(start + def)
                        .append(" low=").append(low).append(" high=").append(high);
                for (int k = 0; k <= high - low; k++) {
                    int off = (int) u4(code, p);
                    p += 4;
                    line.append(' ').append(low + k).append("->").append(start + off);
                }
            } else if (op == 0xab) {
                p += (4 - (p % 4)) % 4;
                int def = (int) u4(code, p);
                p += 4;
                int npairs = (int) u4(code, p);
                p += 4;
                line.append("default->").append(start + def);
                for (int k = 0; k < npairs; k++) {
                    int key = (int) u4(code, p);
                    p += 4;
                    int off = (int) u4(code, p);
                    p += 4;
                    line.append(' ').append(key).append("->").append(start + off);
                }
            } else if (op == 0xbb || op == 0xc0 || op == 0xc1 || op == 0xbd) {
                line.append(resolveConstant(cp, refIndex, u2(code, p)));
                p += 2;
            } else if (op == 0x84) {
                line.append((code[p] & 0xFF)).append(' ').append((int) code[p + 1]);
                p += 2;
            } else if (op == 0xb1 || op == 0xac || op == 0xb0) {
                // 普通返回指令，无操作数
            } else {
                p += n;
            }
            if (op == 0x03 || op == 0x04 || op == 0x05 || op == 0x06 || op == 0x07 || op == 0x08) {
                // 已打印数值
            }
            System.out.println(line);
            if (p <= start) {
                p = start + 1; // 兜底，避免死循环
            }
        }
    }

    private static Object resolveConstant(Object[] cp, int[] refIndex, int idx) {
        Object value = idx > 0 && idx < cp.length ? cp[idx] : null;
        if (value == null && idx > 0 && idx < refIndex.length && refIndex[idx] > 0) {
            Object target = cp[refIndex[idx]];
            if (target != null) {
                return target;
            }
        }
        return value == null ? ("#" + idx) : value;
    }

    private static String member(Object[] cp, int[] refIndex, int[] memberClass, int[] memberNameType,
                                 int[] ntName, int[] ntDesc, int idx) {
        if (idx <= 0 || idx >= cp.length || memberNameType[idx] == 0) {
            return "#" + idx;
        }
        String owner = String.valueOf(resolveConstant(cp, refIndex, memberClass[idx]));
        int nt = memberNameType[idx];
        String memberName = ntName[nt] > 0 ? String.valueOf(cp[ntName[nt]]) : "?";
        String desc = ntDesc[nt] > 0 ? String.valueOf(cp[ntDesc[nt]]) : "";
        return owner + "." + memberName + desc;
    }

    private static String opName(int op) {
        switch (op) {
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
            case 0x13: return "ldc_w";
            case 0x14: return "ldc2_w";
            case 0x2a: return "aload_0";
            case 0x2b: return "aload_1";
            case 0x2c: return "aload_2";
            case 0x2d: return "aload_3";
            case 0x3b: return "istore_0";
            case 0x3c: return "istore_1";
            case 0x3d: return "istore_2";
            case 0x3e: return "istore_3";
            case 0x4b: return "astore_0";
            case 0x4c: return "astore_1";
            case 0x4d: return "astore_2";
            case 0x57: return "pop";
            case 0x59: return "dup";
            case 0x60: return "iadd";
            case 0x64: return "isub";
            case 0x68: return "imul";
            case 0x6c: return "idiv";
            case 0x84: return "iinc";
            case 0x99: return "ifeq";
            case 0x9a: return "ifne";
            case 0x9b: return "iflt";
            case 0x9c: return "ifge";
            case 0x9d: return "ifgt";
            case 0x9e: return "ifle";
            case 0x9f: return "if_icmpeq";
            case 0xa0: return "if_icmpne";
            case 0xa1: return "if_icmplt";
            case 0xa2: return "if_icmpge";
            case 0xa3: return "if_icmpgt";
            case 0xa4: return "if_icmple";
            case 0xa5: return "if_acmpeq";
            case 0xa6: return "if_acmpne";
            case 0xa7: return "goto";
            case 0xac: return "ireturn";
            case 0xb0: return "areturn";
            case 0xb1: return "return";
            case 0xb2: return "getstatic";
            case 0xb3: return "putstatic";
            case 0xb4: return "getfield";
            case 0xb5: return "putfield";
            case 0xb6: return "invokevirtual";
            case 0xb7: return "invokespecial";
            case 0xb8: return "invokestatic";
            case 0xb9: return "invokeinterface";
            case 0xba: return "invokedynamic";
            case 0xbb: return "new";
            case 0xbc: return "newarray";
            case 0xbd: return "anewarray";
            case 0xc0: return "checkcast";
            case 0xc1: return "instanceof";
            case 0xc6: return "ifnull";
            case 0xc7: return "ifnonnull";
            case 0xaa: return "tableswitch";
            case 0xab: return "lookupswitch";
            case 0x15: return "iload";
            case 0x16: return "lload";
            case 0x17: return "fload";
            case 0x18: return "dload";
            case 0x19: return "aload";
            case 0x36: return "istore";
            case 0x37: return "lstore";
            case 0x38: return "fstore";
            case 0x39: return "dstore";
            case 0x3a: return "astore";
            default: return String.format("op_%02x", op);
        }
    }

    /** 除已特殊处理的指令外，该指令后面的操作数字节数。 */
    private static int operands(int op) {
        if (op == 0x10 || op == 0x12) return 1;
        if (op == 0x11 || op == 0x13 || op == 0x14) return 2;
        if (op == 0x15 || op == 0x16 || op == 0x17 || op == 0x18 || op == 0x19
                || op == 0x36 || op == 0x37 || op == 0x38 || op == 0x39 || op == 0x3a) return 1;
        if (op >= 0xb2 && op <= 0xb8) return 2;
        if (op == 0xb9 || op == 0xba) return 4;
        if (op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1) return 2;
        if (op == 0xbc) return 1;
        if (op == 0xc5) return 3;
        if (op == 0xc8 || op == 0xc9) return 4;
        return 0;
    }

    private static int u2(byte[] code, int p) {
        return ((code[p] & 0xFF) << 8) | (code[p + 1] & 0xFF);
    }

    private static long u4(byte[] code, int p) {
        return ((long) (u2(code, p)) << 16) | (u2(code, p + 2) & 0xFFFFL);
    }

    private static String utf8(Object[] cp, int idx) {
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
