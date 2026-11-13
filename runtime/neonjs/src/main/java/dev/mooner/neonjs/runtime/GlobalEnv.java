package dev.mooner.neonjs.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Global declarative and object records; derived from NeonJS and adapted to Java. */
public final class GlobalEnv extends Env {
    public final Realm realm;
    public final JSObject global;
    public final Object thisValue;
    public final HashMap<String, GlobalBinding> lexical = new HashMap<>();
    public final ArrayList<GlobalBinding> lexicalList = new ArrayList<>();
    public int lexicalEpoch;
    public final Object token = new Object();
    public final HashSet<String> varNames = new HashSet<>();

    public GlobalEnv(Realm realm, JSObject global, Object thisValue) {
        super(null);
        this.realm = realm;
        this.global = global;
        this.thisValue = thisValue;
    }

    public void declareLexical(String name, GlobalBinding binding) {
        GlobalBinding previous = lexical.put(name, binding);
        if (previous == null) lexicalList.add(binding);
        else lexicalList.set(lexicalList.indexOf(previous), binding);
        lexicalEpoch++;
    }
}
