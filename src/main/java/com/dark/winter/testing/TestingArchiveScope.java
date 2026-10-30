// Carries explicit per-thread access to an archive namespace.
package com.dark.winter.testing;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit per-thread archive access. No game, online, cryptography or process-global current archive. */
public final class TestingArchiveScope {
    interface Access {
        Path archive();Path prefabs();Path legacy();String authority();Map<String,String> aliases();void check()throws IOException;boolean owns(Path world);String canonical(String source);
    }
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final Map<Path,Access> GUARDED=new ConcurrentHashMap<>();
    @FunctionalInterface public interface IO<T>{T run()throws IOException;}
    private TestingArchiveScope(){}
    static synchronized void guard(Path path,Access access)throws IOException{
        Access previous=GUARDED.get(path);
        if(previous!=null&&previous!=access){boolean active=true;try{previous.check();}catch(IOException detached){active=false;}if(active)throw new IOException("World already has another archive namespace");}
        if(previous==null&&GUARDED.size()>=2048)throw new IOException("Archive world guard quota");GUARDED.put(path,access);
    }
    static Scope enter(Access access)throws IOException{access.check();Scope next=new Scope(access,CURRENT.get());CURRENT.set(next);return next;}
    public static final class Scope implements AutoCloseable {
        private final Access access;private final Scope previous;private final Thread thread=Thread.currentThread();private boolean closed;
        private Scope(Access access,Scope previous){this.access=access;this.previous=previous;}
        @Override public void close(){if(closed)return;if(thread!=Thread.currentThread()||CURRENT.get()!=this)throw new IllegalStateException("Archive scopes must unwind on their owner thread");closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static final class Token {
        private final Access access;private Token(Access access){this.access=access;}
        public Scope enter()throws IOException{return TestingArchiveScope.enter(access);}
        public <T>T call(IO<T> work)throws IOException{try(Scope ignored=TestingArchiveScope.enter(access)){T result=work.run();access.check();return result;}}
        public Runnable wrap(Runnable work){return ()->{try(Scope ignored=TestingArchiveScope.enter(access)){work.run();access.check();}catch(IOException denied){throw new UncheckedIOException(denied);}};}
    }
    public static Optional<Token> capture(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();unchecked(scope.access);return Optional.of(new Token(scope.access));}
    public static Optional<Path> root(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();unchecked(scope.access);return Optional.of(scope.access.archive());}
    public static Optional<Path> prefabs(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();unchecked(scope.access);return Optional.of(scope.access.prefabs());}
    public static Optional<Path> legacy(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();unchecked(scope.access);return Optional.of(scope.access.legacy());}
    public static void checkCurrent()throws IOException{Scope current=CURRENT.get();if(current!=null)current.access.check();}
    public static void requireWorld(Path requested)throws IOException{
        if(requested==null)return;Path world=requested.toAbsolutePath().normalize();Access required=GUARDED.get(world);if(required==null)return;
        Scope current=CURRENT.get();if(current==null||current.access!=required)throw new IOException("Explicit archive context is required for this world");
        required.check();if(!required.owns(world))throw new IOException("Archive world binding is unavailable");
    }
    public static Optional<String> authorityId(){Scope scope=CURRENT.get();if(scope==null)return Optional.empty();unchecked(scope.access);return Optional.of(scope.access.authority());}
    public static Map<String,String> sourceAliases(){Scope scope=CURRENT.get();if(scope==null)return Map.of();unchecked(scope.access);return Map.copyOf(scope.access.aliases());}
    public static boolean includesWorld(Path world){Scope scope=CURRENT.get();if(scope==null)return true;unchecked(scope.access);return scope.access.owns(world.toAbsolutePath().normalize());}
    public static String canonicalSource(String source){Scope scope=CURRENT.get();if(scope==null)return source;unchecked(scope.access);return scope.access.canonical(source);}
    public static boolean sameSource(String a,String b){return Objects.equals(canonicalSource(a),canonicalSource(b));}
    private static void unchecked(Access access){try{access.check();}catch(IOException denied){throw new UncheckedIOException(denied);}}
}
