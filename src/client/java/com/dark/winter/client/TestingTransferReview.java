// Binds transfer confirmation to verified source bytes and the native target signature.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable confirmation bound to actual transfer bytes and a native target signature. */
final class TestingTransferReview {
    record Snapshot(Path target,Map<String,Path> files,Map<String,String> hashes,List<String> lines,
                    List<String> conflicts,String signature,boolean previouslyFrozen) {
        Snapshot {
            target=target.toAbsolutePath().normalize();files=Map.copyOf(files);hashes=Map.copyOf(hashes);
            lines=List.copyOf(lines);conflicts=List.copyOf(conflicts);
            if(signature==null)throw new IllegalArgumentException("Missing native target signature");
        }
        boolean canApply(){return conflicts.isEmpty()&&!hashes.isEmpty();}
        Snapshot failed(String reason){
            var detail=new ArrayList<>(lines);detail.add("Подтверждение отменено: "+reason);
            return new Snapshot(target,files,hashes,detail,List.of(reason),signature,previouslyFrozen);
        }
    }
    private TestingTransferReview(){}
    static Snapshot capture(Path target,Map<String,Path> files,List<String> lines,List<String> conflicts,
                            String signature,boolean previouslyFrozen)throws IOException {
        return new Snapshot(target,files,hashes(files),lines,conflicts,signature,previouslyFrozen);
    }
    static void verify(Snapshot snapshot)throws IOException {
        if(!snapshot.hashes().equals(hashes(snapshot.files())))
            throw new IOException("Файл категории изменился после обзора; подготовьте перенос заново");
    }
    private static Map<String,String> hashes(Map<String,Path> files)throws IOException {
        if(files.isEmpty()||files.size()>9)throw new IOException("Недопустимое число категорий обзора");
        Path directory=TestingArchive.root().resolve("transfers").toAbsolutePath().normalize();
        Map<String,String> result=new TreeMap<>();long total=0;
        for(var entry:files.entrySet()){
            Path file=entry.getValue().toAbsolutePath().normalize();
            if(!file.getParent().equals(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Недопустимый путь категории обзора");
            long bytes=Files.size(file);total+=bytes;
            if(bytes>128L*1024*1024||total>256L*1024*1024)throw new IOException("Категории обзора превышают предел данных");
            result.put(entry.getKey(),TestingArchive.hash(file));
        }
        return Map.copyOf(result);
    }
}
