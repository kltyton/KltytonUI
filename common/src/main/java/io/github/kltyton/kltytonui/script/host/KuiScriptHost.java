package io.github.kltyton.kltytonui.script.host;

/** Java objects exposed as mutable browser host objects to page scripts. */
public interface KuiScriptHost {
    @dev.latvian.mods.rhino.util.HideFromJS
    ScriptHostBindings getScriptBindings();
}
