"use strict";
(function () {
    const LOCAL_API_ERROR = "当前 APK 尚不支持手机本地 HTTP 接口，请更新支持本地 API 的 APK；仅更新网站无法解除旧壳的网络限制。";
    function isLoopbackApiUrl(value) {
        try {
            const url = new URL(value);
            return (url.protocol === "http:" || url.protocol === "https:") &&
                ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname.toLowerCase());
        }
        catch {
            return false;
        }
    }
    // One multiplexed listener; each request owns its stream and cancellation signal.
    const listeners = new Map();
    let installedBridge;
    function install(bridge) {
        if (installedBridge === bridge)
            return;
        installedBridge = bridge;
        bridge.onmessage = event => {
            try {
                const reply = JSON.parse(event.data);
                listeners.get(reply.id)?.(reply);
            }
            catch { /* Ignore malformed messages without logging credentials. */ }
        };
    }
    async function localApiFetch(url, init = {}, mcp = false) {
        if (typeof window === "undefined")
            return fetch(url, init);
        const host = window;
        const bridge = host.FloatLocalApi;
        if (!bridge) {
            if (host.AndroidShell)
                throw new Error(LOCAL_API_ERROR);
            try {
                return await fetch(url, { ...init, credentials: "omit" });
            }
            catch (error) {
                if (init.signal?.aborted)
                    throw error;
                throw new Error("无法连接手机本地 API：请确认本机服务已启动，并允许网站跨域访问及浏览器的本地网络权限。可尝试支持本地 API 的新版 APK。");
            }
        }
        install(bridge);
        const signal = init.signal;
        if (signal?.aborted)
            throw signal.reason ?? new DOMException("Aborted", "AbortError");
        if (init.body != null && typeof init.body !== "string")
            throw new Error("本地 API 仅支持 JSON 文本请求");
        const id = crypto.randomUUID();
        const send = (type, extra = {}) => bridge.postMessage(JSON.stringify({ type, id, ...extra }));
        return new Promise((resolve, reject) => {
            let controller;
            let settled = false;
            let finishPull;
            let timer;
            const cleanup = () => {
                clearTimeout(timer);
                finishPull?.();
                listeners.delete(id);
                signal?.removeEventListener("abort", abort);
            };
            const fail = (error) => {
                cleanup();
                try {
                    send("cancel");
                }
                catch { /* Native side has its own timeout. */ }
                if (settled)
                    controller?.error(error);
                else
                    reject(error);
            };
            const abort = () => fail(signal?.reason ?? new DOMException("Aborted", "AbortError"));
            listeners.set(id, reply => {
                if (reply.type === "error") {
                    fail(new Error("API 连接失败或超时，请检查地址、密钥、网络与服务状态。"));
                }
                else if (reply.type === "headers" && !settled) {
                    clearTimeout(timer);
                    const status = reply.status ?? 500;
                    const noBody = [204, 205, 304].includes(status);
                    const stream = noBody ? null : new ReadableStream({
                        start(c) { controller = c; },
                        // ReadableStream calls pull only when capacity is available.
                        pull() {
                            return new Promise(done => { finishPull = done; send("pull"); });
                        },
                        cancel() { cleanup(); send("cancel"); },
                    });
                    settled = true;
                    resolve(new Response(stream, {
                        status,
                        headers: { "Content-Type": reply.contentType || "application/json", ...(reply.sessionId ? {"Mcp-Session-Id":reply.sessionId} : {}) },
                    }));
                    if (noBody) {
                        cleanup();
                        send("cancel");
                    }
                }
                else if (reply.type === "chunk" && controller) {
                    const bytes = Uint8Array.from(atob(reply.data || ""), c => c.charCodeAt(0));
                    controller.enqueue(bytes);
                    const done = finishPull;
                    finishPull = undefined;
                    done?.();
                }
                else if (reply.type === "end") {
                    cleanup();
                    controller?.close();
                }
            });
            timer = setTimeout(() => fail(new Error("手机本地 API 响应超时，请检查本机服务")), /\/images\/generations(?:\?|$)/.test(url) ? 240_000 : 35_000);
            signal?.addEventListener("abort", abort, { once: true });
            try {
                send("start", {
                    url, method: init.method || "GET",
                    headers: Object.fromEntries(new Headers(init.headers).entries()),
                    body: init.body ?? null, mcp,
                });
            }
            catch (error) {
                fail(error);
            }
        });
    }
    window.XXJ_nativeMcpFetch = (url, init) => localApiFetch(url, init, true);
    window.XXJ_configureMcpTransport = urls => window.FloatLocalApi?.postMessage(JSON.stringify({id:'mcp-config',type:'mcp-configure',urls}));
    const originalFetch = window.fetch.bind(window);
    window.fetch = function (input, init = {}) {
        let url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href);
        if (!window.AndroidShell || !window.FloatLocalApi)
            return originalFetch(input, init);
        if (url.origin === location.origin && url.pathname === '/api/minimax-tts') {
            if ((init.method || 'GET').toUpperCase() === 'GET') {
                if (url.searchParams.get('diag') === '1') {
                    return localApiFetch('https://api.minimaxi.com/v1/t2a_v2', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:'speech-2.8-hd',text:'diag',stream:false,voice_setting:{voice_id:'female-shaonv'},audio_setting:{format:'mp3'}}),signal:init.signal}).then(async response => new Response(JSON.stringify({ok:true,diag:true,local:true,upstream_status:response.status,upstream_body_preview:(await response.text()).slice(0,200)}),{headers:{'Content-Type':'application/json'}})).catch(error=>new Response(JSON.stringify({ok:false,diag:true,error:error.message}),{headers:{'Content-Type':'application/json'}}));
                }
                return Promise.resolve(new Response(JSON.stringify({ ok: true, service: 'minimax-native-transport', local: true }), { headers: { 'Content-Type': 'application/json' } }));
            }
            url = new URL('https://api.minimaxi.com/v1/t2a_v2' + url.search);
        }
        if (!/\/(?:models|chat\/completions|embeddings|messages|images\/generations|audio\/speech|t2a_v2)$/.test(url.pathname) && !/\/models\/[^/]+:(?:streamGenerateContent|generateContent)$/.test(url.pathname))
            return originalFetch(input, init);
        if (input instanceof Request)
            return originalFetch(input, init);
        return localApiFetch(url.href, init);
    };
})();

/* Keep existing ZIP/JSON/image downloads usable in WebView. */
(function(){
  if(!window.AndroidShell?.saveBase64File)return;
  document.addEventListener('click',event=>{
    const link=event.target.closest?.('a[download]');
    if(!link||!link.download||! /^(blob:|data:)/.test(link.href))return;
    event.preventDefault();
    const href=link.href,name=link.download;
    fetch(href).then(r=>r.blob()).then(blob=>new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=reject;reader.readAsDataURL(blob)})).then(base64=>{
      if(window.AndroidShell.saveBase64File(name,base64))window.showToast?.('已保存到手机“下载”目录');else window.showToast?.('保存未成功，请检查存储空间');
    }).catch(()=>window.showToast?.('无法保存文件，请重试'));
  },true);
})();
