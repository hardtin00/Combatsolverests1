import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.*;

/** Checks recorder structure without judging simulator accuracy. */
public final class RecordIntegrityCheck {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length == 0 ? "tools/solvertest/fixtures/corpus" : args[0]);
        boolean failed = false;
        boolean strict = args.length > 1 && "--strict".equals(args[1]);
        for (File f : dir.listFiles()) if (f.getName().endsWith(".jsonl")) failed |= check(f, strict);
        if (failed) System.exit(1);
    }
    private static boolean check(File f, boolean strict) throws Exception {
        byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
        Charset encoding = isUtf8(raw) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
        BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(raw), encoding));
        int line=0, roots=0, actions=0, broken=0; Map<String,Object> previous=null; String key=null;
        String s;
        while((s=r.readLine())!=null){ line++; if(s.trim().isEmpty()) continue;
            Map<String,Object> x; try{x=Json.asMap(Json.parse(s));}catch(RuntimeException e){broken++; continue;}
            String type=Json.str(x,"type","");
            if("root".equals(type)){ roots++; key=Json.str(x,"battleKey",""); if(strict && key.isEmpty()) broken++; if(roots>1) broken++; previous=Json.obj(x,"state"); continue; }
            if(!"card".equals(type)&&!"endTurn".equals(type)) continue; actions++;
            Map<String,Object> pre=Json.obj(x,"pre");
            if(pre.isEmpty() || Json.obj(x,"post").isEmpty()) { broken++; System.out.println(f.getName()+":"+line+" missing pre/post"); }
            if("card".equals(type) && (!Json.bool(x,"paired",true) || Json.obj(x,"card").isEmpty())) { broken++; System.out.println(f.getName()+":"+line+" invalid card action"); }
            if(previous!=null&&!previous.equals(pre)){broken++; System.out.println(f.getName()+":"+line+" pre!=previousPost/root");}
            previous=Json.obj(x,"post");
        }
        r.close();
        String cls=roots==0?"damaged-or-unknown":(broken>0?"single-step-only-or-damaged":"continuous-candidate");
        System.out.println(f.getName()+" roots="+roots+" actions="+actions+" broken="+broken+" class="+cls+" battleKey="+key);
        return roots != 1 || actions == 0 || broken > 0;
    }
    private static boolean isUtf8(byte[] b) {
        try { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); return true; }
        catch (java.nio.charset.CharacterCodingException e) { return false; }
    }
}
