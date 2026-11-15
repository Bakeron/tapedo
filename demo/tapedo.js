// Tapedo in the browser: the app's screens rebuilt 1:1 from ui/ (Compose) with the app's own texts
// (strings.js, generated from res/values). Everything lives in memory only - a reload brings back the
// sample cards. Recording, AI calls and mail do not work here; their buttons say so in a bubble.
(function () {
  "use strict";

  var STR = window.TAPEDO_STRINGS;
  // Until the fonts are in, the page is only its background - dark at once on a dark system (no light flash).
  if (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) document.body.classList.remove("paper");
  var NS = "http://www.w3.org/2000/svg";
  var UNDO_MS = 10000;
  var USD_PLN = 4.0;
  var VERSION = "2.2";
  var PRIVACY_URL = "https://tapedo.szymonbakonis.pl/privacy.html";
  var MANUAL_URL = "https://tapedo.szymonbakonis.pl/instrukcja/";

  // Texts of the demo itself (not in the app).
  var DEMO = {
    pl: {
      mic: "To wersja demonstracyjna - mikrofon tu nie działa. W prawdziwej aplikacji tak: naciskasz i mówisz, a z nagrania powstaje karteczka.",
      off: "W wersji demonstracyjnej nie działa - w prawdziwej aplikacji tak.",
      title: "Tapedo - wersja demonstracyjna",
      manual: "Instrukcja ze zrzutami"
    },
    en: {
      mic: "This is a demo version - the microphone does not work here. In the real app it does: press, speak, and the recording becomes a card.",
      off: "Doesn't work in the demo version - it does in the real app.",
      title: "Tapedo - demo version",
      manual: "User guide with screenshots (Polish)"
    }
  };

  // core/model/Note.kt order - the type tapes follow it.
  var TYPES = ["NOTE", "CHECKLIST", "TASK", "IDEA", "EVENT", "SHOPPING"];
  // ui/home/CardStack.kt StackTab order.
  var TABS = ["SHOPPING", "CHECKLIST", "TASK", "EVENT", "NOTE", "IDEA", "DONE"];
  var PRIORITIES = ["HIGH", "MEDIUM", "LOW"];
  var GROUPS = ["PRODUCE", "BAKERY", "DAIRY", "MEAT", "FROZEN", "PANTRY", "DRINKS", "HOUSEHOLD", "OTHER"];
  // core/settings/AiProvider.kt
  var PROVIDERS = [
    { id: "GROQ", t: "groq", free: true, model: "openai/gpt-oss-120b", key: "https://console.groq.com/keys",
      saved: ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3.8-27b"] },
    { id: "OPENROUTER", t: "openrouter", free: true, model: "openrouter/free", key: "https://openrouter.ai/keys",
      saved: ["openrouter/free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "nvidia/nemotron-3.5-super-vl:free"] },
    { id: "ANTHROPIC", t: "anthropic", free: false, model: "claude-sonnet-5-5", key: "https://platform.claude.com/settings/keys", saved: [] },
    { id: "OPENAI", t: "openai", free: false, model: "gpt-6-luna", key: "https://platform.openai.com/api-keys", saved: [] },
    { id: "GEMINI", t: "gemini", free: false, model: "gemini-3.5-flash-lite", key: "https://aistudio.google.com/apikey", saved: [] }
  ];
  var TEXT_SCALES = [0.9, 1, 1.15, 1.3];
  var MAX_RECORDING = [10, 20, 30, 40, 50];
  for (var m = 1; m <= 60; m++) MAX_RECORDING.push(m * 60);
  var SILENCE = []; for (var s3 = 3; s3 <= 30; s3++) SILENCE.push(s3);
  var PAUSE = []; for (var p0 = 0; p0 <= 10; p0++) PAUSE.push(p0);
  var AI_LIMITS = [10, 25, 50, 100, 200, 300, 500, 1000];

  // ---------- State ----------

  function pickLang() {
    var q = new URLSearchParams(location.search).get("lang");
    if (q === "pl" || q === "en") return q;
    var nav = (navigator.languages && navigator.languages[0]) || navigator.language || "pl";
    return nav.toLowerCase().indexOf("pl") === 0 ? "pl" : "en";
  }
  function todayEpoch() {
    var d = new Date();
    return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 864e5);
  }

  var lang = pickLang();
  var st = {
    lang: lang,
    notes: window.TAPEDO_SAMPLE(lang, todayEpoch(), Date.now()),
    settings: {
      recordingEnd: "SILENCE", maxRecordingSeconds: 60, silenceSeconds: 7, pauseSeconds: 2,
      speechLanguage: lang === "pl" ? "pl-PL" : "en-US", onlineSpeech: "ASK",
      noteTypeChoice: "TASK", theme: "SYSTEM", reduceMotion: false, aiProviders: {}, aiModels: {},
      aiDailyLimit: 200, textScale: 1, whisper: null, tiltedPaper: false, topUpCents: null, topUpDay: null
    },
    screen: "home", onDone: false, query: "", tour: 0,
    editing: null, openedDone: false,
    deleted: null, moved: null,
    setTab: "RECORDING", explain: {}, ai: {}
  };
  PROVIDERS.forEach(function (p) {
    st.ai[p.id] = { open: false, stepsOpen: true, draft: "", models: false, modelSaved: false };
  });
  st.nextId = st.notes.length + 1;

  // ---------- Texts ----------

  function fmt(s, args) {
    var i = 0;
    return s.replace(/%(?:(\d+)\$)?([sd%])/g, function (_, pos, t) {
      if (t === "%") return "%";
      var a = pos ? args[pos - 1] : args[i++];
      return String(a);
    });
  }
  function S(key) {
    var v = STR[st.lang][key];
    if (v == null) return key;
    return fmt(v, Array.prototype.slice.call(arguments, 1));
  }
  function quantity(n) {
    if (st.lang === "en") return n === 1 ? "one" : "other";
    if (n === 1) return "one";
    var d = n % 10, h = n % 100;
    if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "few";
    return "many";
  }
  function P(key, n) {
    var forms = STR[st.lang][key];
    var f = forms[quantity(n)] || forms.other;
    return fmt(f, Array.prototype.slice.call(arguments, 2));
  }
  function D(key) { return DEMO[st.lang][key]; }
  function upper(s) { return s.toLocaleUpperCase(st.lang === "pl" ? "pl-PL" : "en-GB"); }
  function locale() { return st.lang === "pl" ? "pl-PL" : "en-GB"; }

  var typeKey = { NOTE: "note_type_note", CHECKLIST: "note_type_checklist", TASK: "note_type_task", IDEA: "note_type_idea", EVENT: "note_type_event", SHOPPING: "note_type_shopping" };
  var tabKey = { SHOPPING: "tab_shopping", CHECKLIST: "tab_checklist", TASK: "tab_task", EVENT: "tab_event", NOTE: "tab_note", IDEA: "tab_idea", DONE: "tab_done" };
  var prioKey = { HIGH: "priority_high", MEDIUM: "priority_medium", LOW: "priority_low" };
  function typeName(t) { return S(typeKey[t]); }
  function tabName(t) { return S(tabKey[t]); }
  function priorityName(p) { return S(prioKey[p]); }
  function groupName(g) { return S("group_" + (g || "OTHER").toLowerCase()); }
  function numberedName(type, n) { return S("card_numbered", type === "CHECKLIST" ? S("default_checklist_title") : typeName(type), n); }
  function providerName(id) { return S("ai_p_" + id.toLowerCase()); }

  // ---------- Dates (ui/cards/CardText.kt) ----------

  function utcDate(epoch) { return new Date(epoch * 864e5); }
  function part(epoch, opts) { return new Intl.DateTimeFormat(locale(), Object.assign({ timeZone: "UTC" }, opts)).format(utcDate(epoch)); }
  function clockTime(min) { return Math.floor(min / 60) + ":" + String(min % 60).padStart(2, "0"); }
  function dueText(epoch) {
    var diff = epoch - todayEpoch();
    if (diff === 0) return S("card_due_today");
    if (diff === 1) return S("card_due_tomorrow");
    if (diff === -1) return S("card_due_yesterday");
    return part(epoch, { weekday: "short" }) + " " + utcDate(epoch).getUTCDate() + " " + part(epoch, { month: "short" });
  }
  function metaLine(n) {
    var a = [];
    if (n.dueEpochDay != null) a.push(dueText(n.dueEpochDay));
    if (n.timeMinutes != null) a.push(clockTime(n.timeMinutes));
    if (n.place) a.push(n.place);
    return a.join(" · ");
  }

  // ---------- Notes ----------

  function hasItems(t) { return t === "CHECKLIST" || t === "SHOPPING"; }
  function isDone(n) {
    return (n.type === "TASK" && n.done) || (hasItems(n.type) && n.items.length > 0 && n.items.every(function (i) { return i.checked; }));
  }
  function holds(tab, n) { return tab === "DONE" ? isDone(n) : !isDone(n) && n.type === tab; }
  function matches(n, q) {
    q = q.trim().toLowerCase();
    if (!q) return true;
    return n.title.toLowerCase().indexOf(q) >= 0 || n.body.toLowerCase().indexOf(q) >= 0 ||
      (n.place || "").toLowerCase().indexOf(q) >= 0 || n.items.some(function (i) { return i.text.toLowerCase().indexOf(q) >= 0; });
  }
  function sorted() {
    return st.notes.slice().sort(function (a, b) { return b.createdAtMillis - a.createdAtMillis || b.id - a.id; });
  }
  function kindNumbers() {
    var out = {}, by = {};
    st.notes.forEach(function (n) { (by[n.type] = by[n.type] || []).push(n); });
    Object.keys(by).forEach(function (t) {
      by[t].sort(function (a, b) { return a.createdAtMillis - b.createdAtMillis || a.id - b.id; })
        .forEach(function (n, i) { out[n.id] = i + 1; });
    });
    return out;
  }
  function groupedItems(n) {
    var all = n.items.map(function (it, i) { return { i: i, item: it }; });
    if (n.type !== "SHOPPING") return [{ group: null, items: all }];
    var out = [];
    GROUPS.forEach(function (g) {
      var inG = all.filter(function (x) { return (x.item.group || "OTHER") === g; });
      if (inG.length) out.push({ group: g, items: inG });
    });
    return out;
  }
  function firstWords(t) {
    var w = t.trim().split(/\s+/).filter(Boolean);
    return w.slice(0, 6).join(" ") + (w.length > 6 ? "…" : "");
  }
  function tidy(n) {
    var items = n.items.map(function (i) { return Object.assign({}, i, { text: i.text.trim() }); }).filter(function (i) { return i.text; });
    var title = n.title.trim() || firstWords(n.body.trim() || (items[0] ? items[0].text : ""));
    return Object.assign({}, n, { title: title, body: n.body.trim(), items: items, place: n.place && n.place.trim() ? n.place.trim() : null });
  }
  function changeType(n, type) {
    if (type === n.type) return n;
    if (hasItems(type) && n.items.length === 0 && n.body.trim()) {
      var parts = n.body.split(/[\n,;]/).map(function (x) { return x.trim(); }).filter(Boolean);
      if (parts.length > 1) {
        return Object.assign({}, n, { type: type, body: "", items: parts.map(function (t) { return { text: t, checked: false, group: type === "SHOPPING" ? "OTHER" : null }; }) });
      }
    }
    return Object.assign({}, n, { type: type });
  }
  function replaceNote(n) {
    st.notes = st.notes.map(function (x) { return x.id === n.id ? n : x; });
  }
  function shareText(n) {
    var out = [n.title];
    var meta = metaLine(n);
    if (meta) out.push(meta);
    var norm = function (s) { return s.trim().replace(/[.!?…]+$/, "").trim().toLowerCase(); };
    if (n.body.trim() && norm(n.body) !== norm(n.title)) { out.push(""); out.push(n.body); }
    if (n.items.length) {
      out.push("");
      groupedItems(n).forEach(function (g) {
        if (g.group) out.push(groupName(g.group) + ":");
        g.items.forEach(function (x) { out.push((x.item.checked ? "☑ " : "☐ ") + x.item.text); });
      });
    }
    return out.join("\n").trim();
  }

  // ---------- DOM helpers ----------

  function props(el, p) {
    if (!p) return;
    Object.keys(p).forEach(function (k) {
      var v = p[k];
      if (v == null || v === false) return;
      if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.slice(0, 2) === "on" && typeof v === "function") el.addEventListener(k.slice(2), v);
      else if (k === "value" && (el.tagName === "INPUT" || el.tagName === "TEXTAREA")) el.value = v;
      else el.setAttribute(k, v === true ? "" : v);
    });
  }
  function add(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(el, x); }); return; }
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  function h(tag, p) {
    var el = document.createElement(tag);
    props(el, p);
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function sv(tag, p) {
    var el = document.createElementNS(NS, tag);
    props(el, p);
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function still() {
    return st.settings.reduceMotion || window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }
  function tilt(deg) { return st.settings.tiltedPaper ? deg : 0; }
  function rot(deg) { return deg ? "rotate(" + deg + "deg)" : ""; }
  var uid = 0;

  // Values from the previous frame, so a control can glide from where it was (Compose animates state changes).
  var memo = new Map(), nextMemo = new Map(), hooks = [];
  function was(key, value) { nextMemo.set(key, value); return memo.get(key); }
  function after(f) { hooks.push(f); }
  function nextFrame(f) { requestAnimationFrame(function () { requestAnimationFrame(f); }); }

  // ---------- Drawn signs (ui/cards/TypeMark.kt, Paper.kt, PriorityTape.kt) ----------

  function typeMark(type, size) {
    var w = size, sw = Math.max(w / 10, 1.4);
    var g = sv("svg", { width: w, height: w, viewBox: "0 0 " + w + " " + w, "aria-hidden": "true", fill: "none", stroke: "currentColor",
      "stroke-width": sw, "stroke-linecap": "round", "stroke-linejoin": "round", style: "flex:none;overflow:visible;display:block" });
    function X(f) { return (f * w).toFixed(2); }
    function line(x1, y1, x2, y2, extra) { g.appendChild(sv("line", Object.assign({ x1: X(x1), y1: X(y1), x2: X(x2), y2: X(y2) }, extra || {}))); }
    switch (type) {
      case "SHOPPING":
        g.appendChild(sv("path", { d: "M" + X(.08) + " " + X(.42) + "L" + X(.92) + " " + X(.42) + "L" + X(.80) + " " + X(.90) + "L" + X(.20) + " " + X(.90) + "Z" }));
        line(.40, .52, .42, .80); line(.60, .52, .58, .80);
        g.appendChild(sv("path", { d: "M" + X(.24) + " " + X(.40) + "A" + X(.26) + " " + X(.30) + " 0 0 1 " + X(.76) + " " + X(.40) }));
        break;
      case "CHECKLIST":
        for (var i = 0; i < 3; i++) {
          var y = .20 + .30 * i;
          g.appendChild(sv("rect", { x: X(.04), y: X(y - .09), width: X(.18), height: X(.18), "stroke-width": sw * .8, "stroke-linecap": "butt", "stroke-linejoin": "miter" }));
          line(.36, y, .96, y);
        }
        break;
      case "TASK":
        g.appendChild(sv("path", { d: "M" + X(.10) + " " + X(.52) + "L" + X(.40) + " " + X(.82) + "L" + X(.92) + " " + X(.18), "stroke-width": sw * 1.3 }));
        break;
      case "EVENT":
        g.appendChild(sv("rect", { x: X(.08), y: X(.18), width: X(.84), height: X(.76), rx: X(.08) }));
        g.appendChild(sv("rect", { x: X(.08), y: X(.18), width: X(.84), height: X(.20), fill: "currentColor", stroke: "none" }));
        line(.30, .06, .30, .28); line(.70, .06, .70, .28);
        g.appendChild(sv("rect", { x: X(.56), y: X(.60), width: X(.18), height: X(.18), fill: "currentColor", stroke: "none" }));
        break;
      case "NOTE":
        [.94, .94, .94, .56].forEach(function (end, i) { var yy = .16 + .23 * i; line(.06, yy, end, yy); });
        break;
      case "IDEA":
        g.appendChild(sv("circle", { cx: X(.5), cy: X(.36), r: X(.30) }));
        line(.38, .72, .62, .72); line(.40, .86, .60, .86); line(.42, .62, .40, .72); line(.58, .62, .60, .72);
        break;
      case "DONE":
        g.appendChild(sv("rect", { x: X(.04), y: X(.14), width: X(.92), height: X(.22), rx: X(.04) }));
        g.appendChild(sv("rect", { x: X(.12), y: X(.36), width: X(.76), height: X(.54), "stroke-linecap": "butt", "stroke-linejoin": "miter" }));
        line(.38, .56, .62, .56);
        break;
    }
    return g;
  }

  function pencilBox(checked) {
    var g = sv("svg", { width: 20, height: 20, viewBox: "0 0 20 20", "aria-hidden": "true", fill: "none", stroke: "currentColor",
      "stroke-linecap": "round", "stroke-linejoin": "round", style: "flex:none;overflow:visible;display:block" },
      sv("path", { d: "M1.6 2.4L18.4 1.6L18 18.4L2 18Z", "stroke-width": 1.6 }));
    if (checked) g.appendChild(sv("path", { d: "M4.4 10.4L8.8 14.8L17.2 3.6", "stroke-width": 2.4 }));
    return g;
  }

  function priorityTape(priority, w, h, cls, angle) {
    var tooth = 2.5, d = "M0 0L" + w + " 0", y = 0, out = true;
    while (y < h) { y = Math.min(y + tooth, h); d += "L" + (out ? w - tooth : w) + " " + y; out = !out; }
    d += "L0 " + h; y = h; out = true;
    while (y > 0) { y = Math.max(y - tooth, 0); d += "L" + (out ? tooth : 0) + " " + y; out = !out; }
    d += "Z";
    var id = "tp" + (++uid);
    var fill = { HIGH: "var(--tapeHigh)", MEDIUM: "var(--tapeMedium)", LOW: "var(--tapeLow)" }[priority];
    var pattern = sv("g", { "clip-path": "url(#" + id + ")", fill: "var(--tapeInk)", stroke: "var(--tapeInk)", "fill-opacity": .6, "stroke-opacity": .6 });
    if (priority === "HIGH") {
      for (var x = -h; x < w + h; x += 6) pattern.appendChild(sv("line", { x1: x, y1: h, x2: x + h, y2: 0, "stroke-width": 2 }));
    } else if (priority === "MEDIUM") {
      var row = 0;
      for (var yy = 3; yy < h; yy += 6) {
        for (var xx = row % 2 === 0 ? 3 : 6; xx < w; xx += 6) pattern.appendChild(sv("circle", { cx: xx, cy: yy, r: 1.3, stroke: "none" }));
        row++;
      }
    }
    return sv("svg", { class: cls, width: w, height: h, viewBox: "0 0 " + w + " " + h, role: "img", "aria-label": S("card_priority_desc", priorityName(priority)),
      style: "overflow:visible;transform:" + (rot(tilt(angle == null ? 38 : angle)) || "none") },
      sv("defs", null, sv("clipPath", { id: id }, sv("path", { d: d }))),
      sv("path", { d: d, fill: fill, "fill-opacity": .92 }),
      pattern);
  }

  function settingsIcon() {
    return sv("svg", { viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "aria-hidden": "true" },
      sv("circle", { cx: 12, cy: 12, r: 7, "stroke-width": 2 }),
      sv("path", { d: "M12 12L15.4 7.6", "stroke-width": 2.2, "stroke-linecap": "round" }),
      sv("path", { d: "M12 1.8L12 3M19.2 4.8L18.4 5.6M22.2 12L21 12M4.8 4.8L5.6 5.6M1.8 12L3 12", "stroke-width": 1.6, "stroke-linecap": "round" }));
  }
  function backIcon() {
    return sv("svg", { viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "aria-hidden": "true" },
      sv("path", { d: "M19.5 12.3L5.2 11.8M10.8 5.8L4.9 11.8L11.2 18.1", "stroke-width": 2.2, "stroke-linecap": "round", "stroke-linejoin": "round" }));
  }
  function screw() {
    return sv("svg", { width: 9, height: 9, viewBox: "0 0 9 9", "aria-hidden": "true", style: "overflow:visible;flex:none" },
      sv("circle", { cx: 4.5, cy: 4.5, r: 4.5, fill: "var(--knob)", stroke: "var(--controlEdge)", "stroke-width": 1 }),
      sv("line", { x1: 4.5 - 2.52, y1: 4.5 - 1.89, x2: 4.5 + 2.52, y2: 4.5 + 1.89, stroke: "var(--controlEdge)", "stroke-width": 1.2 }));
  }

  // ---------- Small components ----------

  function iconBtn(icon, label, onClick) {
    return h("button", { class: "iconBtn", type: "button", "aria-label": label, title: label, onclick: onClick }, icon);
  }
  function textBtn(text, label, onClick, cls) {
    return h("button", { class: "iconBtn titleLarge variant " + (cls || ""), type: "button", "aria-label": label, title: label, onclick: onClick }, h("span", { "aria-hidden": "true" }, text));
  }
  // PaperButton; disabled ones stay focusable but do nothing, like Compose's disabled clickable.
  function pbtn(text, onClick, style, opts) {
    opts = opts || {};
    var b = h("button", { class: "pbtn labelLarge " + (style || "") + (opts.wide ? " wide" : ""), type: "button", "aria-disabled": opts.disabled ? "true" : null, style: opts.style || null },
      text);
    b.addEventListener("click", function (e) { if (b.getAttribute("aria-disabled") !== "true") onClick(e, b); });
    return b;
  }
  function labelTape(text) {
    return h("h3", { class: "labelTape labelLarge", style: { margin: 0, transform: rot(tilt(-1.2)) || "none" } }, text);
  }
  function stamp() {
    return h("span", { class: "stamp labelMedium", role: "img", "aria-label": S("card_ai_desc"), style: { transform: rot(tilt(-4)) || "none" } }, S("card_ai_stamp"));
  }
  // Padding around ruled text in the app wraps the trimmed text - so a margin here is less the trim.
  function trimmed(px) { return "calc(" + px + " - var(--trim))"; }
  function ruled(text, cls, color, extra) {
    var style = { color: color || "var(--onSurfaceVariant)" };
    Object.keys(extra || {}).forEach(function (k) { style[k] = /^margin(Top|Bottom)$/.test(k) ? trimmed(extra[k]) : extra[k]; });
    var el = h("p", { class: cls + " ruled pre", style: style });
    add(el, text);
    return el;
  }
  function warning(text) {
    return h("div", { class: "row warn" }, h("span", { class: "bang labelMedium", "aria-hidden": "true" }, "!"), h("span", { class: "bodySmall grow" }, text));
  }

  // Fold: unfolds downward and fades in; folds back the same way (ui/components/Fold.kt).
  function fold(key, open, build) {
    var prev = was("fold:" + key, open);
    if (open) {
      var el = h("div", { "data-fold": key }, build());
      if (prev === false && !still()) after(function () {
        var hgt = el.scrollHeight;
        el.style.overflow = "hidden";
        el.animate([{ height: "0px", opacity: 0 }, { height: hgt + "px", opacity: 1 }], { duration: 220, easing: "ease-in-out" })
          .onfinish = function () { el.style.overflow = ""; };
      });
      return el;
    }
    if (prev === true && !still()) {
      var ghost = h("div", { "data-fold": key, "aria-hidden": "true", style: { pointerEvents: "none" } });
      after(function (root, old) {
        var o = old && old.querySelector('[data-fold="' + CSS.escape(key) + '"]');
        if (!o) { ghost.remove(); return; }
        Array.prototype.forEach.call(o.childNodes, function (c) { ghost.appendChild(c.cloneNode(true)); });
        var hgt = ghost.scrollHeight;
        ghost.style.overflow = "hidden";
        ghost.animate([{ height: hgt + "px", opacity: 1 }, { height: "0px", opacity: 0, offset: 1 }], { duration: 220, easing: "ease-in-out", fill: "forwards" })
          .onfinish = function () { ghost.remove(); };
      });
      return ghost;
    }
    return null;
  }
  function foldLabel(text, open, key) {
    var prev = was("arrow:" + key, open);
    var start = prev != null && prev !== open && !still() ? prev : open;
    // "▸" comes from the phone's fallback font there - a small triangle, drawn here.
    var arrow = h("span", { class: "arrow", "aria-hidden": "true", style: { transform: start ? "rotate(90deg)" : "none" } },
      sv("svg", { width: 4, height: 5.4, viewBox: "0 0 4 5.4", style: "display:block" }, sv("path", { d: "M0 0L4 2.7L0 5.4Z", fill: "currentColor" })));
    if (start !== open) after(function () { nextFrame(function () { arrow.style.transform = open ? "rotate(90deg)" : "none"; }); });
    return h("span", { class: "row foldLabel labelLarge" }, arrow, h("span", { class: "underline" }, text));
  }

  // SlideSelector: the knob slides to the new option; its LED dims and lights up on the way.
  function slide(key, options, selected, labelFn, onSelect) {
    var idx = Math.max(0, options.indexOf(selected));
    var prev = was("slide:" + key, idx);
    var from = prev != null && prev !== idx && !still() ? prev : idx;
    var n = options.length;
    var knob = h("div", { class: "knob", style: { width: (100 / n) + "%", transform: "translateX(" + (from * 100) + "%)" } });
    var opts = options.map(function (o, i) {
      var b = h("button", { type: "button", class: "opt labelLarge" + (i === idx ? " sel" : ""), role: "radio", "aria-checked": String(i === idx),
        onclick: function () { if (i !== idx) onSelect(o, i); } },
        h("span", { class: "led", style: { background: i === from ? "var(--record)" : "var(--ledOff)" } }), h("span", null, labelFn(o)));
      return b;
    });
    if (from !== idx) after(function () {
      nextFrame(function () {
        knob.style.transform = "translateX(" + (idx * 100) + "%)";
        opts.forEach(function (b, i) { b.firstChild.style.background = i === idx ? "var(--record)" : "var(--ledOff)"; });
      });
    });
    return h("div", { class: "slide", role: "radiogroup" }, h("div", { class: "knobArea" }, knob), h("div", { class: "opts" }, opts));
  }

  // ToggleLever: flipped up is on, down is off.
  function lever(key, checked, onChange, label, allLabels, ariaLabel) {
    var prev = was("lever:" + key, checked);
    var from = prev != null && prev !== checked && !still() ? prev : checked;
    function armT(on) { return on ? "none" : "scaleY(-1)"; }
    function tipT(on) { return on ? "none" : "translateY(32px)"; }
    var arm = sv("g", { class: "arm", style: "transform-origin:22px 28px;transform:" + armT(from) },
      sv("line", { x1: 22, y1: 28, x2: 22, y2: 12, stroke: "var(--onSurface)", "stroke-width": 4, "stroke-linecap": "round" }));
    var tip = sv("g", { class: "arm", style: "transform:" + tipT(from) },
      sv("circle", { cx: 22, cy: 12, r: 5, fill: "var(--onSurface)" }),
      sv("circle", { cx: 20.5, cy: 10.5, r: 1.5, fill: "var(--knobLight)" }));
    var svg = sv("svg", { width: 44, height: 56, viewBox: "0 0 44 56", "aria-hidden": "true", style: "overflow:visible;flex:none" },
      sv("rect", { x: 4, y: 2, width: 36, height: 52, rx: 4, fill: "var(--track)", stroke: "var(--controlEdge)", "stroke-width": 1 }),
      sv("circle", { cx: 22, cy: 7, r: 2, fill: "var(--controlEdge)" }),
      sv("circle", { cx: 22, cy: 49, r: 2, fill: "var(--controlEdge)" }),
      sv("circle", { cx: 22, cy: 28, r: 8, fill: "var(--knob)", stroke: "var(--outline)", "stroke-width": 1.5 }),
      arm, tip);
    if (from !== checked) after(function () { nextFrame(function () { arm.style.transform = armT(checked); tip.style.transform = tipT(checked); }); });
    var words = h("span", { class: "words labelLarge bold" });
    (allLabels || []).forEach(function (w) { words.appendChild(h("span", { style: { visibility: "hidden" }, "aria-hidden": "true" }, w)); });
    words.appendChild(h("span", null, label));
    return h("button", { type: "button", class: "row lever" + (checked ? " on" : ""), role: "switch", "aria-checked": String(checked), "aria-label": ariaLabel || null,
      onclick: function () { onChange(!checked); } }, svg, h("span", { class: "ledDot" }), words);
  }

  // CounterSlider: a groove with a scale and the red dot; the value is typed above the dot.
  function counter(key, options, selected, labelFn, onSelect, tickFn, spokenFn) {
    var idx = Math.max(0, options.indexOf(selected));
    var last = options.length - 1;
    var groove = h("div", { class: "groove" });
    var wound = h("div", { style: { position: "absolute", left: 0, top: 0, bottom: 0, borderRadius: "7px", background: "color-mix(in srgb, var(--record) 35%, transparent)" } });
    groove.appendChild(h("div", { style: { position: "absolute", inset: 0, borderRadius: "7px", background: "var(--track)" } }));
    groove.appendChild(wound);
    for (var i = 1; i < last; i++) {
      var t = tickFn ? tickFn(options[i]) : "NONE";
      if (t === "NONE") continue;
      var hh = t === "MAJOR" ? 9.8 : 5.6;
      groove.appendChild(h("div", { style: { position: "absolute", left: (i / last * 100) + "%", top: ((14 - hh) / 2) + "px", height: hh + "px", width: "1px", marginLeft: "-.5px", background: "var(--onSurfaceVariant)" } }));
    }
    groove.appendChild(h("div", { style: { position: "absolute", inset: 0, borderRadius: "7px", border: "1px solid var(--controlEdge)" } }));
    var readout = h("span", { class: "readout bodyMedium" });
    var thumb = h("div", { class: "thumb" }, readout, h("div", { class: "dot" }));
    var area = h("div", { class: "area" }, groove, thumb);
    var el = h("div", { class: "counter", role: "slider", tabindex: 0, "aria-valuemin": 0, "aria-valuemax": last });
    el.appendChild(area);
    function show(k) {
      var f = last === 0 ? 0 : k / last;
      wound.style.width = (f * 100) + "%";
      thumb.style.left = "calc(15px + (100% - 30px) * " + f + ")";
      readout.textContent = labelFn(options[k]);
      readout.style.left = (30 * f) + "px";
      readout.style.transform = "translateX(" + (-100 * f) + "%)";
      el.setAttribute("aria-valuenow", k);
      el.setAttribute("aria-valuetext", (spokenFn || labelFn)(options[k]));
    }
    show(idx);
    var cur = idx;
    function at(x) {
      var r = area.getBoundingClientRect();
      var f = Math.min(1, Math.max(0, (x - r.left - 15) / (r.width - 30)));
      return Math.round(f * last);
    }
    el.addEventListener("pointerdown", function (e) {
      el.setPointerCapture(e.pointerId);
      cur = at(e.clientX); show(cur);
      function move(ev) { cur = at(ev.clientX); show(cur); }
      function up() {
        el.removeEventListener("pointermove", move);
        el.removeEventListener("pointerup", up);
        el.removeEventListener("pointercancel", up);
        if (cur !== idx) onSelect(options[cur]);
      }
      el.addEventListener("pointermove", move);
      el.addEventListener("pointerup", up);
      el.addEventListener("pointercancel", up);
    });
    el.addEventListener("keydown", function (e) {
      var k = cur;
      if (e.key === "ArrowRight" || e.key === "ArrowUp") k = Math.min(last, cur + 1);
      else if (e.key === "ArrowLeft" || e.key === "ArrowDown") k = Math.max(0, cur - 1);
      else if (e.key === "Home") k = 0;
      else if (e.key === "End") k = last;
      else return;
      e.preventDefault();
      if (k !== cur) { cur = k; onSelect(options[k]); }
    });
    return el;
  }

  // ---------- The demo bubble ----------

  var bubbleEl = null, bubbleTimer = 0;
  function bubble(target, text) {
    hideBubble();
    var app = document.getElementById("app");
    var b = h("div", { class: "bubble bodySmall", role: "status", "aria-live": "polite" }, text);
    app.appendChild(b);
    var a = app.getBoundingClientRect(), t = target.getBoundingClientRect();
    var bw = b.offsetWidth, bh = b.offsetHeight;
    var cx = t.left + t.width / 2 - a.left;
    var left = Math.min(Math.max(8, cx - bw / 2), a.width - bw - 8);
    var top = t.top - a.top - bh - 10;
    if (top < 8) { top = t.bottom - a.top + 10; b.classList.add("below"); }
    b.style.left = left + "px";
    b.style.top = top + "px";
    b.style.setProperty("--ax", Math.min(Math.max(14, cx - left), bw - 14) + "px");
    if (!still()) b.animate([{ opacity: 0, transform: "translateY(4px)" }, { opacity: 1, transform: "none" }], { duration: 160, easing: "ease-out" });
    bubbleEl = b;
    bubbleTimer = setTimeout(hideBubble, 5000);
  }
  function hideBubble() {
    clearTimeout(bubbleTimer);
    if (!bubbleEl) return;
    var b = bubbleEl;
    bubbleEl = null;
    if (still()) { b.remove(); return; }
    b.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 140 }).onfinish = function () { b.remove(); };
  }
  document.addEventListener("pointerdown", function (e) {
    if (bubbleEl && !bubbleEl.contains(e.target)) hideBubble();
  }, true);
  function demoOff(e, el) { bubble(el || e.currentTarget, D("off")); }

  // ---------- First steps (ui/home/FirstSteps.kt) ----------

  // The demo shows them on every start - it remembers nothing. "Back" (left arrow) goes one slip back,
  // the right arrow one on, Escape skips, as on the phone.
  var TOUR = [
    { spot: "RECORD", key: "record" }, { spot: "TABS", key: "tabs" }, { spot: "BOX", key: "box" },
    { spot: null, key: "priority" }, { spot: "SETTINGS", key: "settings" }
  ];
  var TOUR_DRAW_MS = 520, HOLE_PAD = 6, SMALL_SPOT = 140, MARKER_WIDTH = 4, ARROW_GAP = 56, SLIP_MAX_WIDTH = 380;

  function touring() { return st.tour != null && st.screen === "home"; }
  function tourNext() { if (st.tour === TOUR.length - 1) tourEnd(); else { st.tour++; render(); } }
  function tourBack() { if (st.tour === 0) tourEnd(); else { st.tour--; render(); } }
  function tourEnd() { st.tour = null; pendingFocus = '[data-tour="RECORD"]'; render(); }
  function tourKey(e) {
    var f = { ArrowRight: tourNext, ArrowLeft: tourBack, Escape: tourEnd }[e.key];
    if (!f || e.altKey || e.ctrlKey || e.metaKey) return;
    e.preventDefault();
    if (!e.repeat) f();
  }
  function clamp(v, lo, hi) { return Math.min(Math.max(v, lo), Math.max(lo, hi)); }
  function overlaps(a, b) { return a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b; }

  function tourOverlay(root) {
    var step = TOUR[st.tour], last = st.tour === TOUR.length - 1;
    var prev = was("tour", st.tour);
    var shade = sv("svg", { class: "tourShade", "aria-hidden": "true" });
    var slip = tipSlip(step, last);
    var el = h("div", { class: "tour", role: "dialog", "aria-modal": "true", "aria-label": S("first_steps_pane"), tabindex: -1 }, shade, slip);
    after(function () {
      var o = el.getBoundingClientRect(), W = o.width, H = o.height;
      var spotEl = step.spot && root.querySelector('[data-tour="' + step.spot + '"]');
      var mark = null;
      if (spotEl) {
        var r = spotEl.getBoundingClientRect();
        mark = markFor({ l: r.left - o.left, t: r.top - o.top, r: r.right - o.left, b: r.bottom - o.top }, W, H);
      }
      var sw = slip.offsetWidth, sh = slip.offsetHeight;
      var at = placeSlip(mark && markBounds(mark), sw, sh, { l: 16, t: 16, r: W - 16, b: H - 16 }, ARROW_GAP);
      slip.style.left = at.x + "px";
      slip.style.top = at.y + "px";
      var slipRect = { l: at.x, t: at.y, r: at.x + sw, b: at.y + sh };

      shade.setAttribute("viewBox", "0 0 " + W + " " + H);
      // The clear window has the marker's shape: an oval inside the loop, or the frame's rectangle.
      var hole = !mark ? "" : mark.small ? ellipse(mark.cx, mark.cy, mark.rx * .97, mark.ry * .97) : roundRect(mark.frame, 12);
      shade.appendChild(sv("path", { d: "M0 0H" + W + "V" + H + "H0Z" + hole, fill: "rgba(0,0,0,.62)", "fill-rule": "evenodd" }));
      var animate = prev !== st.tour && !still();
      if (mark) {
        var loop = sv("path", { d: mark.small ? markerLoop(mark) : roundRect(mark.frame, 12), fill: "none", stroke: "var(--tapeMedium)",
          "stroke-width": MARKER_WIDTH, "stroke-linecap": "round", "stroke-linejoin": "round" });
        shade.appendChild(loop);
        if (animate) {
          var len = loop.getTotalLength();
          loop.style.strokeDasharray = len;
          loop.animate([{ strokeDashoffset: len }, { strokeDashoffset: 0 }], { duration: TOUR_DRAW_MS, fill: "backwards" });
        }
        if (!overlaps(slipRect, markBounds(mark))) {
          var arrow = pencilArrow(slipRect, markBounds(mark));
          if (arrow) {
            shade.appendChild(arrow);
            if (animate) arrow.animate([{ opacity: 0 }, { opacity: 1 }], { duration: TOUR_DRAW_MS * .45, delay: TOUR_DRAW_MS * .55, fill: "backwards" });
          }
        }
      }
      if (animate) slip.animate([{ opacity: 0 }, { opacity: 1 }], { duration: TOUR_DRAW_MS / 2.5 });
      // The tips hold the keyboard at once, so the arrow keys work; Tab reaches the keys on the slip.
      if (prev == null && !el.contains(document.activeElement)) el.focus({ preventScroll: true });
    });
    return el;
  }

  // One slip: the step on a label tape, the title, the text typed on the card's lines, then the keys.
  function tipSlip(step, last) {
    var app = document.getElementById("app");
    var slip = h("div", { class: "tipSlip", style: { width: Math.max(0, Math.min(app.clientWidth - 32, SLIP_MAX_WIDTH)) + "px", transform: rot(tilt(-0.8)) || "none" } },
      labelTape(S("first_steps_count", st.tour + 1, TOUR.length)),
      h("div", { class: "col", style: { gap: "6px" } },
        h("h2", { class: "titleMedium", id: "tourTitle", style: { margin: 0 } }, S("first_steps_" + step.key + "_title")),
        ruled(S(step.key === "record" && st.settings.recordingEnd === "STOP_BUTTON" ? "first_steps_record_body_stop" : "first_steps_" + step.key + "_body"),
          "bodyMedium marginLine", "var(--onSurface)")));
    slip.lastChild.lastChild.id = "tourBody";
    if (step.key === "priority") {
      var legend = h("div", { class: "col", style: { gap: "8px" } });
      [["HIGH", "high"], ["MEDIUM", "medium"], ["LOW", "low"]].forEach(function (p) {
        var tape = priorityTape(p[0], 44, 16, "", 0);
        tape.setAttribute("aria-hidden", "true");
        legend.appendChild(h("div", { class: "row", style: { gap: "12px" } }, tape,
          h("span", { class: "bodyMedium grow", style: { margin: 0 } }, S("first_steps_priority_" + p[1]))));
      });
      slip.appendChild(legend);
    }
    var next = pbtn(S(last ? "first_steps_done" : "first_steps_next"), tourNext, "filled");
    // Read with the slip's text when it gets the focus back after each step.
    next.setAttribute("data-focus", "tourNext");
    next.setAttribute("aria-describedby", "tourTitle tourBody");
    var keys = last ? [next] : [pbtn(S("first_steps_skip"), tourEnd), next];
    if (!last) keys[0].setAttribute("data-focus", "tourSkip");
    var pair = pairOrStack(keys, 8);
    pair.style.marginTop = "4px";
    slip.appendChild(pair);
    return slip;
  }

  // Below or above the spot (on the side with more room first), else beside it; a spot as big as the screen
  // gets the slip over its lower part. Never out of the area. Same as placeSlip in FirstSteps.kt.
  function placeSlip(t, w, hgt, area, gap) {
    function cx(x) { return clamp(x, area.l, area.r - w); }
    function cy(y) { return clamp(y, area.t, area.b - hgt); }
    if (!t) return { x: cx((area.l + area.r - w) / 2), y: cy((area.t + area.b - hgt) / 2) };
    var x = cx((t.l + t.r - w) / 2);
    var below = t.b + gap, above = t.t - gap - hgt;
    var fitsBelow = below + hgt <= area.b, fitsAbove = above >= area.t;
    if ((t.t + t.b) / 2 < (area.t + area.b) / 2 && fitsBelow) return { x: x, y: below };
    if (fitsAbove) return { x: x, y: above };
    if (fitsBelow) return { x: x, y: below };
    var y = cy((t.t + t.b - hgt) / 2);
    if (t.l - gap - w >= area.l) return { x: t.l - gap - w, y: y };
    if (t.r + gap + w <= area.r) return { x: t.r + gap, y: y };
    return { x: x, y: cy(t.b - hgt - gap / 2) };
  }

  // A hand-drawn oval around a small spot (the key, the gear), a rounded frame around a wide one.
  function markFor(t, W, H) {
    var hole = { l: t.l - HOLE_PAD, t: t.t - HOLE_PAD, r: t.r + HOLE_PAD, b: t.b + HOLE_PAD };
    if (Math.max(hole.r - hole.l, hole.b - hole.t) > SMALL_SPOT) {
      var e = MARKER_WIDTH;
      return { small: false, frame: { l: Math.max(hole.l - 3, e), t: Math.max(hole.t - 3, e), r: Math.min(hole.r + 3, W - e), b: Math.min(hole.b + 3, H - e) } };
    }
    var rx = (hole.r - hole.l) / 2 + 2, ry = (hole.b - hole.t) / 2 + 2, k = 1.12;
    // The gear sits in the corner: the oval moves in a little rather than run off the screen.
    return { small: true, rx: rx, ry: ry,
      cx: clamp((hole.l + hole.r) / 2, rx * k, W - rx * k), cy: clamp((hole.t + hole.b) / 2, ry * k, H - ry * k) };
  }
  // Everything the marker covers, its wobble included - the slip and the arrow keep out of it.
  function markBounds(m) {
    return m.small ? { l: m.cx - m.rx * 1.12, t: m.cy - m.ry * 1.12, r: m.cx + m.rx * 1.12, b: m.cy + m.ry * 1.12 } : m.frame;
  }
  function ellipse(cx, cy, rx, ry) {
    return "M" + (cx - rx) + " " + cy + "A" + rx + " " + ry + " 0 1 0 " + (cx + rx) + " " + cy + "A" + rx + " " + ry + " 0 1 0 " + (cx - rx) + " " + cy + "Z";
  }
  function roundRect(f, r) {
    return "M" + (f.l + r) + " " + f.t + "H" + (f.r - r) + "A" + r + " " + r + " 0 0 1 " + f.r + " " + (f.t + r) +
      "V" + (f.b - r) + "A" + r + " " + r + " 0 0 1 " + (f.r - r) + " " + f.b + "H" + (f.l + r) +
      "A" + r + " " + r + " 0 0 1 " + f.l + " " + (f.b - r) + "V" + (f.t + r) + "A" + r + " " + r + " 0 0 1 " + (f.l + r) + " " + f.t + "Z";
  }
  // An oval that overshoots its start, as drawn by hand; wider at the end, so the overshoot does not lie on the start.
  function markerLoop(m) {
    var d = "", start = -Math.PI * .6, turn = 2 * Math.PI * 1.12, n = 72;
    for (var i = 0; i <= n; i++) {
      var t = i / n, a = start + turn * t, wob = 1 + .035 * Math.sin(a * 3) + .06 * t;
      d += (i ? "L" : "M") + (m.cx + Math.cos(a) * m.rx * wob).toFixed(1) + " " + (m.cy + Math.sin(a) * m.ry * wob).toFixed(1);
    }
    return d;
  }
  // A slightly bent pencil arrow from the slip's edge to the marked spot, stopping a little short of the marker.
  function pencilArrow(slip, spot) {
    var ax = (spot.l + spot.r) / 2, ay = (spot.t + spot.b) / 2;
    var fx = clamp(ax, slip.l, slip.r), fy = clamp(ay, slip.t, slip.b);
    var tx = clamp(fx, spot.l, spot.r), ty = clamp(fy, spot.t, spot.b);
    var len = Math.hypot(tx - fx, ty - fy);
    if (len < 12) return null;
    tx -= (tx - fx) * 6 / len; ty -= (ty - fy) * 6 / len;
    var qx = (fx + tx) / 2 - (ty - fy) * .18, qy = (fy + ty) / 2 + (tx - fx) * .18;
    var angle = Math.atan2(ty - qy, tx - qx), d = "M" + fx + " " + fy + "Q" + qx + " " + qy + " " + tx + " " + ty;
    [-1, 1].forEach(function (side) {
      var a = angle + Math.PI + side * .5;
      d += "M" + tx + " " + ty + "L" + (tx + Math.cos(a) * 11) + " " + (ty + Math.sin(a) * 11);
    });
    return sv("path", { d: d, fill: "none", stroke: "var(--tapeMedium)", "stroke-width": 2.5, "stroke-linecap": "round", "stroke-linejoin": "round" });
  }

  // ---------- Home (ui/home/HomeScreen.kt, CardStack.kt) ----------

  function nameplate() {
    return h("h1", { class: "row nameplate", style: { margin: 0 }, "aria-label": S("app_name") },
      screw(), h("span", { class: "name titleLarge", "aria-hidden": "true" }, upper(S("app_name"))), screw());
  }

  function buildHome() {
    var kind = st.settings.noteTypeChoice || "NOTE";
    var tab = st.onDone ? "DONE" : kind;
    var root = h("div", { class: "screen-home" });
    var gear = iconBtn(settingsIcon(), S("home_open_settings"), function () { go("settings"); });
    gear.setAttribute("data-tour", "SETTINGS");
    root.appendChild(h("div", { class: "row topRow" }, nameplate(), gear));
    var middle = h("div", { class: "middle grow" }, cardStack(tab), stripArea(tab));
    root.appendChild(middle);
    root.appendChild(controls(kind));
    if (touring()) {
      // The screen below takes no touches, no keyboard and no screen reader while the tips are open.
      Array.prototype.forEach.call(root.children, function (c) { c.inert = true; });
      root.appendChild(tourOverlay(root));
    }
    return root;
  }

  function cardStack(tab) {
    var notes = sorted();
    var numbers = kindNumbers();
    var col = h("div", { class: "col grow" });
    var tabs = h("div", { class: "row tabs", role: "tablist", "data-tour": "TABS" });
    TABS.forEach(function (t) {
      var count = notes.filter(function (n) { return holds(t, n); }).length;
      var sel = t === tab;
      tabs.appendChild(h("button", { type: "button", class: "tab" + (sel ? " sel" : ""), role: "tab", "aria-selected": String(sel),
        "aria-label": P("tab_desc", count, tabName(t), count), title: tabName(t),
        onclick: function () { selectTab(t); } },
        h("span", { class: "face" }, typeMark(t, sel ? 20 : 17), h("span", { class: "labelMedium", style: { fontWeight: sel ? 700 : 500 }, "aria-hidden": "true" }, String(count)))));
    });
    col.appendChild(tabs);
    var box = h("div", { class: "box grow", "data-tour": "BOX" });
    if (notes.length) box.appendChild(searchLine());
    var list = h("div", { class: "list scroll grow", "data-scroll": "list" });
    fillList(list, tab, notes, numbers);
    box.appendChild(list);
    col.appendChild(box);
    return col;
  }

  function fillList(list, tab, notes, numbers) {
    list.textContent = "";
    // The strip shows "Zadanie 2", not the title - so that name finds the card too (CardStack.kt).
    var q = st.query.trim().toLowerCase();
    var shown = notes.filter(function (n) {
      return holds(tab, n) && (matches(n, st.query) || (n.type !== "EVENT" && numberedName(n.type, numbers[n.id]).toLowerCase().indexOf(q) >= 0));
    });
    if (!shown.length && !st.query.trim() && tab !== "DONE") list.appendChild(emptyCard());
    else if (!shown.length) list.appendChild(h("p", { class: "bodyMedium variant", role: "status", style: { marginInline: 0, padding: "16px 8px" } },
      st.query.trim() ? S("search_none", st.query.trim()) : S("tab_empty")));
    else shown.forEach(function (n) { list.appendChild(noteStrip(n, numbers[n.id])); });
    // Space under the last card for the undo tape, so it never hides the last card for good.
    list.appendChild(h("div", { style: { flex: "none", height: (st.deleted || st.moved) ? "80px" : "0px" } }));
  }

  function searchLine() {
    var input = h("input", { type: "search", class: "bodyMedium", placeholder: S("search_hint"), "aria-label": S("search_hint"), value: st.query, enterkeyhint: "search", autocomplete: "off", "data-focus": "search" });
    var row = h("div", { class: "row search" }, input);
    var clear = textBtn("×", S("search_clear"), function () { st.query = ""; pendingFocus = '[data-focus="search"]'; render(); });
    var spacer = h("span", { style: { width: "14px", flex: "none" } });
    row.appendChild(st.query ? clear : spacer);
    input.addEventListener("input", function () {
      st.query = input.value;
      // Only the list follows the typing - the field keeps its focus and keyboard.
      var list = current.querySelector('[data-scroll="list"]');
      var tab = st.onDone ? "DONE" : (st.settings.noteTypeChoice || "NOTE");
      // Outside render(), so the drawings made after layout (pencil strikes, the dashed frame) run here.
      hooks = [];
      fillList(list, tab, sorted(), kindNumbers());
      var run = hooks;
      hooks = [];
      run.forEach(function (f) { f(current, current); });
      var hasClear = row.lastChild === clear;
      if (st.query && !hasClear) row.replaceChild(clear, row.lastChild);
      if (!st.query && hasClear) row.replaceChild(spacer, row.lastChild);
    });
    input.addEventListener("keydown", function (e) { if (e.key === "Enter") input.blur(); });
    return row;
  }

  function emptyCard() {
    var r = Math.round;
    var svg = sv("svg", { "aria-hidden": "true" });
    after(function () {
      var w = svg.parentNode.offsetWidth, hh = svg.parentNode.offsetHeight;
      svg.setAttribute("viewBox", "0 0 " + w + " " + hh);
      svg.appendChild(sv("rect", { x: 0, y: 0, width: r(w), height: r(hh), rx: 3, fill: "none", stroke: "var(--outline)", "stroke-width": 1.5, "stroke-dasharray": "5 3.6" }));
    });
    return h("div", { class: "emptyCard", style: { transform: rot(tilt(-1.2)) || "none" } }, svg,
      h("div", { class: "titleMedium" }, S("stack_empty_title")),
      ruled(S("home_empty"), "bodyMedium marginLine", "var(--onSurfaceVariant)", { marginTop: "8px" }));
  }

  function noteStrip(note, number) {
    var numbered = note.type !== "EVENT";
    var label = numbered ? numberedName(note.type, number) : note.title;
    var done = isDone(note);
    var meta = metaLine(note);
    var second = numbered ? meta : typeName(note.type) + (meta ? "  ·  " + meta : "");
    var tapeRoom = note.type !== "IDEA" && st.settings.tiltedPaper ? 10 : 0;
    var paper = h("div", { class: "paper", role: "button", tabindex: 0, "aria-label": label + (second ? ", " + second : "") + ". " + S("stack_open_card") });
    paper.addEventListener("click", function () { openCard(note); });
    paper.addEventListener("keydown", function (e) { if ((e.key === "Enter" || e.key === " ") && e.target === paper) { e.preventDefault(); openCard(note); } });
    if (note.type === "TASK") {
      paper.appendChild(h("button", { type: "button", class: "tick", role: "checkbox", "aria-checked": String(note.done), "aria-label": label,
        onclick: function (e) { e.stopPropagation(); toggleDone(note); } }, pencilBox(note.done)));
    }
    var title = h("span", { class: "titleMedium ellipsis", style: { display: "block", color: done ? "var(--onSurfaceVariant)" : "var(--onSurface)" } }, label);
    var titleBox = h("div", { class: "titleWrap" }, title);
    if (done) strike(titleBox, title, "strike:" + note.id);
    var texts = h("div", { class: "texts" }, titleBox);
    if (second) texts.appendChild(h("div", { class: "labelSmall variant ellipsis meta" }, upper(second)));
    paper.appendChild(texts);
    if (hasItems(note.type) && note.items.length) {
      var ticked = note.items.filter(function (i) { return i.checked; }).length;
      paper.appendChild(h("span", { class: "labelLarge", style: { marginLeft: "8px", flex: "none" }, "aria-label": S("strip_progress_desc", ticked, note.items.length) }, ticked + "/" + note.items.length));
    }
    paper.appendChild(h("span", { class: "titleLarge variant", "aria-hidden": "true", style: { marginLeft: "8px", flex: "none" } }, "›"));
    paper.appendChild(textBtn("×", S("strip_delete_desc", label), function (e) { e.stopPropagation(); deleteNote(note); }));
    var wrap = h("div", { class: "strip", "data-flip": "n" + note.id, style: { paddingTop: tapeRoom + "px" } }, paper,
      h("div", { class: "typeTape", "aria-hidden": "true" }, h("div", { class: "typeTapeMark", style: { color: "var(--onPrimary)", transform: rot(tilt(-3)) || "none" } }, typeMark(note.type, 18))));
    if (note.type !== "IDEA") {
      var tp = priorityTape(note.priority, 34, 12, "prio");
      tp.style.top = tapeRoom + "px";
      wrap.appendChild(tp);
    }
    return wrap;
  }

  // A hand-drawn pencil line through the title, drawn left to right when it turns on (Paper.kt handStrike).
  function strike(box, text, key) {
    var prev = was(key, true);
    var svg = sv("svg", { class: "strike", "aria-hidden": "true" });
    box.appendChild(svg);
    after(function () {
      var w = text.scrollWidth > text.offsetWidth ? text.offsetWidth : text.scrollWidth;
      var hh = text.offsetHeight;
      var left = -2, right = w + 2, mid = hh / 2;
      var d = "M" + left + " " + (mid + 1.5);
      for (var i = 1; i <= 6; i++) {
        var x = left + (right - left) * i / 6;
        var wave = i % 2 === 0 ? 0.8 : -0.8;
        d += "L" + x.toFixed(1) + " " + (mid + 1.5 - 3 * i / 6 + wave).toFixed(1);
      }
      svg.setAttribute("width", right + 2);
      svg.setAttribute("height", hh);
      var path = sv("path", { d: d, fill: "none", stroke: "var(--onSurface)", "stroke-width": 1.8, "stroke-linecap": "round", "stroke-linejoin": "round" });
      svg.appendChild(path);
      if (prev === false && !still()) {
        var len = path.getTotalLength();
        path.style.strokeDasharray = len;
        path.animate([{ strokeDashoffset: len }, { strokeDashoffset: 0 }], { duration: 260 });
      }
    });
  }

  function controls(kind) {
    var el = h("div", { class: "controls" });
    var into = S("home_record_into", tabName(kind));
    el.appendChild(h("div", { class: "row into" }, typeMark(kind, 16), h("span", { class: "labelLarge" }, into)));
    var pause = st.settings.pauseSeconds;
    el.appendChild(h("div", { class: "bodySmall variant pauseRule" }, pause > 0 ? S("home_pause_rule", pause) : S("home_pause_off")));
    var key = recordKey(S("record_start"), function (b) { bubble(b, D("mic")); });
    key.setAttribute("data-tour", "RECORD");
    el.appendChild(h("div", { class: "keyCol" }, key, h("span", { class: "labelLarge underKey", "aria-hidden": "true" }, S("record_start"))));
    return el;
  }

  // The red record key in its bezel: it sinks while pressed (RecordButton.kt).
  function recordKey(label, onClick) {
    var cx = 42, cy = 84 * .42, rx = 84 * .32, ry = 84 * .28;
    function pt(a) { var r = a * Math.PI / 180; return (cx + rx * Math.cos(r)).toFixed(2) + " " + (cy + ry * Math.sin(r)).toFixed(2); }
    var glint = sv("path", { d: "M" + pt(215) + "A" + rx + " " + ry + " 0 0 1 " + pt(295), fill: "none", stroke: "#fff", "stroke-opacity": .22, "stroke-width": 84 * .06, "stroke-linecap": "round" });
    var dome = h("span", { class: "dome" }, sv("svg", { viewBox: "0 0 84 84", "aria-hidden": "true" }, glint));
    var b = h("button", { type: "button", class: "recKey", "aria-label": label }, dome);
    function down() { b.classList.add("down"); glint.setAttribute("stroke-opacity", .1); }
    function up() { b.classList.remove("down"); glint.setAttribute("stroke-opacity", .22); }
    b.addEventListener("pointerdown", down);
    b.addEventListener("pointerup", up);
    b.addEventListener("pointerleave", up);
    b.addEventListener("pointercancel", up);
    b.addEventListener("click", function () { if (navigator.vibrate) { try { navigator.vibrate(12); } catch (e) { /* not allowed in a frame */ } } onClick(b); });
    return b;
  }

  // The undo / moved tape at the bottom of the box (BottomStrip.kt).
  function stripArea(tab) {
    var area = h("div", { class: "stripArea" });
    var s = null, key = "none";
    if (st.deleted) {
      key = "deleted-" + st.deleted.note.id + "-" + st.deleted.start;
      s = tapeStrip(key, st.deleted.start, S("stack_deleted", st.deleted.note.title), pbtn(S("stack_undo"), undoDelete, "onInk"), dismissDeleted);
    } else if (st.moved) {
      key = "moved-" + st.moved.note.id + "-" + st.moved.to + "-" + st.moved.start;
      var to = st.moved.to;
      s = tapeStrip(key, st.moved.start, S("stack_moved", tabName(to)),
        to !== tab ? pbtn(S("stack_moved_show"), function () { showMoved(to); }, "onInk") : null, dismissMoved);
    }
    var prev = was("strip", key);
    if (s) area.appendChild(s);
    if (prev != null && prev !== key && !still()) {
      after(function (root, old) {
        var o = old && old.querySelector("[data-strip]");
        if (o && prev !== "none") {
          var ghost = o.cloneNode(true);
          ghost.style.position = "absolute";
          ghost.style.left = "0"; ghost.style.right = "0"; ghost.style.bottom = "0";
          ghost.style.pointerEvents = "none";
          area.appendChild(ghost);
          ghost.animate([{ transform: "none", opacity: 1 }, { transform: "translateY(50%)", opacity: 0 }], { duration: 200, fill: "forwards" }).onfinish = function () { ghost.remove(); };
        }
        if (s) s.animate([{ transform: "translateY(50%)", opacity: 0 }, { transform: "none", opacity: 1 }], { duration: 240, easing: "ease-out" });
      });
    }
    return area;
  }

  function tapeStrip(key, start, text, action, onClose) {
    var line = h("div", { class: "timeline" });
    var elapsed = Date.now() - start;
    after(function () {
      var anim = line.animate([{ transform: "scaleX(1)" }, { transform: "scaleX(0)" }],
        { duration: UNDO_MS, delay: -elapsed, easing: still() ? "steps(10, end)" : "linear", fill: "forwards" });
      anim.onfinish = null;
    });
    var rivet = h("button", { type: "button", class: "rivet", "aria-label": S("strip_close"), onclick: onClose },
      sv("svg", { width: 24, height: 24, viewBox: "0 0 24 24", "aria-hidden": "true" },
        sv("circle", { cx: 12, cy: 12, r: 12, fill: "var(--card)" }),
        sv("circle", { cx: 12, cy: 12, r: 11.25, fill: "none", stroke: "var(--primary)", "stroke-width": 1.5 }),
        sv("path", { d: "M6.96 6.96L17.04 17.04M6.96 17.04L17.04 6.96", stroke: "var(--onSurface)", "stroke-width": 2, "stroke-linecap": "round" })));
    return h("div", { class: "tapeStrip", "data-strip": key },
      h("div", { class: "body" },
        h("div", { class: "row msg", role: "status", "aria-live": "polite" }, h("span", { class: "bodyMedium grow", style: { color: "var(--onPrimary)" } }, text), action),
        line),
      rivet);
  }

  // ---------- Home actions (HomeViewModel.kt) ----------

  var undoTimer = 0, movedTimer = 0;
  function selectTab(t) {
    st.onDone = t === "DONE";
    if (t !== "DONE") st.settings.noteTypeChoice = t;
    render();
  }
  function openCard(note) {
    hideBubble();
    st.openedDone = isDone(note);
    st.editing = JSON.parse(JSON.stringify(note));
    st.optionsOpen = false;
    go("card");
  }
  function toggleDone(note) {
    var after1 = Object.assign({}, note, { done: !note.done });
    replaceNote(after1);
    if (isDone(note) !== isDone(after1)) { showMovedStrip(after1); pendingFocus = "[data-strip] .msg button"; }
    render();
  }
  function showMovedStrip(note) {
    st.moved = { note: note, to: isDone(note) ? "DONE" : note.type, start: Date.now() };
    clearTimeout(movedTimer);
    // Only the home screen shows the tape - re-building another screen would move the caret while typing.
    movedTimer = setTimeout(function () { st.moved = null; if (st.screen === "home") render(); }, UNDO_MS);
  }
  function dismissMoved() { clearTimeout(movedTimer); st.moved = null; render(); }
  function showMoved(to) {
    st.onDone = to === "DONE";
    if (to !== "DONE") st.settings.noteTypeChoice = to;
    clearTimeout(movedTimer);
    st.moved = null;
    render();
  }
  function deleteNote(note) {
    pendingFocus = "[data-strip] .msg button";
    st.notes = st.notes.filter(function (n) { return n.id !== note.id; });
    st.deleted = { note: note, start: Date.now() };
    clearTimeout(undoTimer);
    undoTimer = setTimeout(function () { st.deleted = null; if (st.screen === "home") render(); }, UNDO_MS);
    render();
  }
  function dismissDeleted() { clearTimeout(undoTimer); st.deleted = null; render(); }
  function undoDelete() {
    if (!st.deleted) return;
    st.notes.push(st.deleted.note);
    pendingFocus = '[data-flip="n' + st.deleted.note.id + '"] .paper';
    st.deleted = null;
    clearTimeout(undoTimer);
    render();
  }

  // ---------- Card view (ui/home/CardScreen.kt, ui/cards/EditableCard.kt, CardPaper.kt) ----------

  function edit(changes, rerender) {
    st.editing = Object.assign({}, st.editing, changes);
    replaceNote(st.editing);
    if (rerender) render();
  }
  var lastCardId = null;
  function closeCard() {
    var card = tidy(st.editing);
    lastCardId = card.id;
    replaceNote(card);
    st.editing = null;
    if (isDone(card) !== st.openedDone) showMovedStrip(card);
    go("home");
  }
  function deleteEditing() {
    var note = tidy(st.editing);
    st.editing = null;
    st.screen = "home";
    deleteNote(note);
  }

  function header(title, onBack) {
    return h("div", { class: "row header" }, iconBtn(backIcon(), S("back"), onBack),
      h("h1", { class: "headlineSmall grow", style: { margin: 0 } }, title));
  }

  function buildCard() {
    var note = st.editing;
    var number = kindNumbers()[note.id];
    var root = h("div", { class: "screen-card" });
    root.appendChild(header(S("editor_title"), closeCard));
    var col = h("div", { class: "cardCol" });
    col.appendChild(editableCard(note, number));
    // A list has "Add by voice" inside the card, next to "Add item"; a text card keeps the wide key and the hint.
    if (!hasItems(note.type)) {
      col.appendChild(h("div", { class: "voice" },
        h("div", { class: "bodySmall variant" }, S("preview_add_hint_text")),
        voiceKey({ wide: true, style: { minHeight: "52px" } })));
    }
    col.appendChild(cardOptions(note));
    root.appendChild(h("div", { class: "scroll grow", "data-scroll": "card" }, col));
    return root;
  }

  function voiceKey(opts) { return pbtn(S("preview_add_voice"), function (e, b) { bubble(b, D("mic")); }, "filled", opts); }

  // Keys side by side, equally wide; when any label is too wide for its half (bigger text), one under the other
  // (ui/components/PairOrStack.kt).
  function pairOrStack(keys, stackGap) {
    var el = h("div", { class: "pair" });
    el.style.setProperty("--stackGap", stackGap + "px");
    keys.forEach(function (k) { el.appendChild(k); });
    after(function () {
      el.classList.remove("stack");
      el.classList.add("measure");
      var half = (el.clientWidth - 12) / keys.length;
      var tooWide = keys.some(function (k) { return k.offsetWidth > half; });
      el.classList.remove("measure");
      if (tooWide) el.classList.add("stack");
    });
    return el;
  }

  function paperWidth(type) { return type === "SHOPPING" ? 86 : type === "IDEA" ? 82 : 100; }

  function cardPaper(note, content) {
    var type = note.type;
    var cls = "cardPaper" + (type === "SHOPPING" ? " receipt" : "") + (type === "CHECKLIST" ? " grid" : "") + (type === "TASK" ? " task" : "") + (type === "IDEA" ? " idea" : "");
    var paper = h("div", { class: cls });
    if (type === "SHOPPING") {
      var bg = sv("svg", { class: "receiptBg", "aria-hidden": "true" });
      paper.appendChild(bg);
      after(function () { receipt(paper, bg); });
    }
    if (type === "TASK") {
      paper.appendChild(sv("svg", { class: "punch", viewBox: "0 0 22 22", "aria-hidden": "true" },
        sv("circle", { cx: 11, cy: 11, r: 9, fill: "none", stroke: "var(--rule)", "stroke-width": 2 }),
        sv("circle", { cx: 11, cy: 11, r: 5, fill: "var(--desk)" })));
    }
    if (type === "EVENT") paper.appendChild(calendarHead(note));
    paper.appendChild(content);
    if (type === "IDEA") {
      paper.appendChild(sv("svg", { class: "pin", viewBox: "0 0 26 26", "aria-hidden": "true" },
        sv("circle", { cx: 15, cy: 14, r: 7, fill: "#000", "fill-opacity": .22 }),
        sv("circle", { cx: 13, cy: 11, r: 7, fill: "var(--record)" }),
        sv("circle", { cx: 13 - 2.24, cy: 11 - 2.24, r: 2.24, fill: "#fff", "fill-opacity": .45 })));
    }
    return paper;
  }

  // A till receipt: straight top, torn-off zigzag bottom - redrawn whenever the paper changes size.
  function receipt(paper, bg) {
    function draw() {
      var w = paper.offsetWidth, hh = paper.offsetHeight, tooth = 6;
      var teeth = Math.max(1, Math.floor(w / (tooth * 2))), step = w / teeth;
      var d = "M0 0L" + w + " 0L" + w + " " + (hh - tooth), x = w;
      for (var i = 0; i < teeth; i++) { d += "L" + (x - step / 2).toFixed(2) + " " + hh; x -= step; d += "L" + x.toFixed(2) + " " + (hh - tooth); }
      d += "Z";
      bg.setAttribute("width", w); bg.setAttribute("height", hh);
      bg.setAttribute("viewBox", "0 0 " + w + " " + hh);
      bg.innerHTML = "";
      bg.appendChild(sv("path", { d: d, fill: "var(--card)", stroke: "var(--cardEdge)", "stroke-width": 1 }));
    }
    draw();
    if (window.ResizeObserver) new ResizeObserver(draw).observe(paper);
  }

  function calendarHead(note) {
    var time = note.timeMinutes != null ? clockTime(note.timeMinutes) : null;
    var spoken = [note.dueEpochDay != null ? dueText(note.dueEpochDay) : null, time].filter(Boolean).join(" ") || typeName(note.type);
    var band = h("div", { class: "row calHead", role: "img", "aria-label": spoken });
    if (note.dueEpochDay != null) {
      var e = note.dueEpochDay;
      band.appendChild(h("span", { class: "displaySmall bold", "aria-hidden": "true" }, String(utcDate(e).getUTCDate())));
      band.appendChild(h("span", { style: { width: "12px", flex: "none" } }));
      band.appendChild(h("span", { class: "col", "aria-hidden": "true" },
        h("span", { class: "labelLarge" }, upper(part(e, { weekday: "long" }))),
        h("span", { class: "labelMedium", style: { whiteSpace: "pre" } }, [part(e, { month: "long" }), time].filter(Boolean).join("  ·  "))));
    } else {
      band.appendChild(h("span", { class: time ? "headlineMedium" : "labelLarge", "aria-hidden": "true" }, time || upper(typeName(note.type))));
    }
    var perforation = h("span", { class: "perforation", "aria-hidden": "true", style: { background: "radial-gradient(circle, var(--desk) 1.6px, transparent 1.9px) 0 0 / 7px 8px repeat-x" } });
    return h("div", null, band, perforation);
  }

  function paperField(label, value, cls, onInput, opts) {
    opts = opts || {};
    var input, lines = null;
    if (opts.singleLine) {
      input = h("input", { type: "text", class: cls, value: value, "aria-label": label, autocapitalize: "sentences" });
    } else {
      input = h("textarea", { class: cls, rows: opts.minLines || 1, "aria-label": label, autocapitalize: "sentences", value: value });
      var grow = function () {
        input.style.height = "auto";
        input.style.height = input.scrollHeight + "px";
        if (lines) {
          // As in the app: a rule every first-line height (that line is trimmed), between the lines only.
          var b = bodyStep.bodyLarge, n = Math.max(opts.minLines || 1, Math.round(input.scrollHeight / b.lh));
          lines.style.height = (b.first * (n - 1) + 0.5) + "px";
          lines.style.backgroundSize = "100% " + b.first + "px";
          lines.style.backgroundImage = "linear-gradient(to bottom, transparent " + (b.first - 1) + "px, var(--rule) " + (b.first - 1) + "px)";
        }
      };
      input.addEventListener("input", grow);
      after(grow);
    }
    var wrap = h("div", { class: "field col" }, h("span", { class: "labelMedium variant" }, label));
    if (opts.ruled) {
      // A faint rule between the lines of a note, spaced by the text's own lines; the line below is the last rule.
      lines = h("div", { style: { position: "absolute", left: 0, right: 0, top: "4px", pointerEvents: "none" } });
      wrap.appendChild(h("div", { style: { position: "relative" } }, lines, input));
    } else {
      wrap.appendChild(input);
    }
    wrap.appendChild(h("div", { class: "line" }));
    input.addEventListener("input", function () { onInput(input.value); });
    input.addEventListener("focus", function () { wrap.classList.add("focus"); });
    input.addEventListener("blur", function () { wrap.classList.remove("focus"); });
    if (opts.key) input.setAttribute("data-focus", opts.key);
    return wrap;
  }

  function editableCard(note, number) {
    var type = note.type;
    var content = h("div", { class: "content" });
    var kind = number != null && type !== "EVENT" ? numberedName(type, number) : typeName(type);
    content.appendChild(h("h2", { class: "labelSmall variant", style: { margin: 0, paddingRight: "52px" } }, upper(kind)));
    content.appendChild(paperField(S("editor_field_title"), note.title, "titleLarge", function (v) { edit({ title: v }); }, { singleLine: true }));
    if (!hasItems(type) || note.body.trim()) {
      content.appendChild(paperField(S("editor_field_body"), note.body, "bodyLarge", function (v) { edit({ body: v }); },
        { minLines: hasItems(type) ? 1 : 3, ruled: type === "NOTE" }));
    }
    if (hasItems(type)) content.appendChild(itemsEditor(note));
    if (type === "EVENT") content.appendChild(paperField(S("editor_field_place"), note.place || "", "bodyLarge", function (v) { edit({ place: v }); }, { singleLine: true }));
    if (note.dueEpochDay != null) content.appendChild(removable(S("editor_due", dueText(note.dueEpochDay)), S("editor_remove_due"), function () { edit({ dueEpochDay: null }, true); }));
    if (note.timeMinutes != null) content.appendChild(removable(S("editor_time", clockTime(note.timeMinutes)), S("editor_remove_time"), function () { edit({ timeMinutes: null }, true); }));
    if (type === "TASK") {
      content.appendChild(h("button", { type: "button", class: "row toggleRow", role: "checkbox", "aria-checked": String(note.done),
        onclick: function () { edit({ done: !note.done }, true); } }, pencilBox(note.done), h("span", { class: "bodyLarge" }, S("editor_task_done"))));
    }
    if (note.byAi) content.appendChild(stamp());
    // As in the app: a crooked tape sticks out about 14px above the card, so the card keeps that room inside it.
    var tapeRoom = type !== "IDEA" && st.settings.tiltedPaper ? 14 : 0;
    var box = h("div", { class: "paperBox", style: { width: paperWidth(type) + "%", paddingTop: tapeRoom + "px" } }, cardPaper(note, content));
    if (type !== "IDEA") {
      var tp = priorityTape(note.priority, 64, 20, "cardPrio");
      tp.style.top = (4 + tapeRoom) + "px";
      box.appendChild(tp);
    }
    return box;
  }

  function removable(label, removeLabel, onRemove) {
    return h("div", { class: "row" }, h("span", { class: "bodyLarge grow" }, label), pbtn(removeLabel, onRemove));
  }

  var focusItem = null;
  function itemsEditor(note) {
    var items = note.items;
    var shopping = note.type === "SHOPPING";
    var el = h("div", { class: "col" });
    groupedItems(note).forEach(function (g) {
      if (g.group) {
        el.appendChild(h("div", { style: { height: "14px" }, "aria-hidden": "true" }));
        el.appendChild(h("h3", { class: "bodySmall bold variant", style: { marginTop: "calc(2px - var(--trim))", marginInline: 0 } }, upper(groupName(g.group))));
      }
      g.items.forEach(function (x) {
        var i = x.i, item = x.item;
        var itemLabel = S("editor_item_label", i + 1);
        var input = h("input", { type: "text", class: "bodyLarge grow", value: item.text, "aria-label": itemLabel, autocapitalize: "sentences", "data-focus": "item" + i });
        input.addEventListener("input", function () {
          edit({ items: st.editing.items.map(function (it, j) { return j === i ? Object.assign({}, it, { text: input.value }) : it; }) });
        });
        if (focusItem === i) after(function () { input.focus(); focusItem = null; });
        el.appendChild(h("div", { class: "row itemRow" },
          h("button", { type: "button", class: "box44", role: "checkbox", "aria-checked": String(item.checked), "aria-label": itemLabel,
            onclick: function () { edit({ items: st.editing.items.map(function (it, j) { return j === i ? Object.assign({}, it, { checked: !it.checked }) : it; }) }, true); } },
            pencilBox(item.checked)),
          input,
          h("button", { type: "button", class: "box44 titleLarge variant", "aria-label": S("editor_remove_item", i + 1),
            onclick: function () { edit({ items: st.editing.items.filter(function (_, j) { return j !== i; }) }, true); } }, h("span", { "aria-hidden": "true" }, "×"))));
      });
    });
    var keys = pairOrStack([pbtn(S("editor_add_item"), function () {
      focusItem = items.length;
      edit({ items: st.editing.items.concat([{ text: "", checked: false, group: shopping ? "OTHER" : null }]) }, true);
    }), voiceKey()], 8);
    keys.style.marginTop = "8px";
    el.appendChild(keys);
    return el;
  }

  // A wide "Show settings" key first; type, priority, Share and Delete only behind it (author 15.11.2026).
  function cardOptions(note) {
    var el = h("div", { class: "options" });
    el.appendChild(pbtn(S(st.optionsOpen ? "editor_options_hide" : "editor_options_show"),
      function () { st.optionsOpen = !st.optionsOpen; render(); }, null, { wide: true }));
    if (!st.optionsOpen) return el;
    var bar = h("div", { class: "row typeBar hscroll", role: "radiogroup", "data-scroll": "typebar" });
    var selEl = null;
    TYPES.forEach(function (t, i) {
      var sel = t === note.type;
      var tape = h("button", { type: "button", class: "typeTape" + (sel ? " sel" : ""), role: "radio", "aria-checked": String(sel),
        onclick: function () { if (!sel) edit(changeType(st.editing, t), true); } },
        h("span", { class: "row face labelLarge", style: { transform: sel ? "none" : (rot(tilt(i % 2 === 0 ? -1.6 : 1.2)) || "none") } },
          h("span", { class: "led" }, h("i")), typeMark(t, 15), h("span", null, upper(typeName(t)))));
      if (sel) selEl = tape;
      bar.appendChild(tape);
    });
    // Keep the chosen tape in view with one neighbour to its left, which shows there is more to swipe.
    var prevType = was("typebar", note.type);
    after(function () {
      if (!selEl) return;
      var bl = bar.scrollLeft, bw = bar.clientWidth, l = selEl.offsetLeft - bar.offsetLeft, r = l + selEl.offsetWidth;
      if (l >= bl && r <= bl + bw) return;
      var i = TYPES.indexOf(note.type), target = bar.children[Math.max(0, i - 1)];
      var x = Math.max(0, target.offsetLeft - bar.offsetLeft - 16);
      bar.scrollTo({ left: x, behavior: prevType != null && prevType !== note.type && !still() ? "smooth" : "auto" });
    });
    el.appendChild(h("div", { class: "col", style: { gap: "8px" } }, labelTape(S("editor_field_type")), bar));
    if (note.type !== "IDEA") {
      el.appendChild(h("div", { class: "col", style: { gap: "10px" } }, labelTape(S("editor_field_priority")),
        slide("priority", PRIORITIES, note.priority, priorityName, function (p) { edit({ priority: p }, true); })));
    }
    el.appendChild(pairOrStack([pbtn(S("card_share"), function (e, b) {
      var n = tidy(st.editing);
      if (navigator.share) {
        navigator.share({ title: n.title, text: shareText(n) }).catch(function (err) { if (err && err.name !== "AbortError") bubble(b, D("off")); });
      } else bubble(b, D("off"));
    }), pbtn(S("editor_delete"), deleteEditing, "danger")], 18));
    return el;
  }

  // ---------- Settings (ui/settings/SettingsScreen.kt, AiSettings.kt) ----------

  var SET_TABS = [["RECORDING", "settings_tab_recording"], ["AI", "settings_tab_ai"], ["LOOK", "settings_tab_look"], ["ABOUT", "settings_tab_about"]];
  function set(changes) { Object.assign(st.settings, changes); render(); }
  // A field left by clicking a key saves on "change" - between the key's press and release. Re-building the
  // screen then would swap the key under the pointer and lose the click, so it waits for the release.
  var pressing = false, renderOnRelease = false;
  document.addEventListener("pointerdown", function () { pressing = true; }, true);
  ["pointerup", "pointercancel"].forEach(function (t) {
    document.addEventListener(t, function () {
      pressing = false;
      if (renderOnRelease) { renderOnRelease = false; setTimeout(render, 0); }
    }, true);
  });
  function renderAfterPress() { if (pressing) renderOnRelease = true; else render(); }

  function buildSettings() {
    var root = h("div", { class: "screen-settings" });
    root.appendChild(header(S("settings_title"), function () { go("home"); }));
    var tabs = h("div", { class: "row setTabs hscroll", role: "tablist" });
    SET_TABS.forEach(function (t) {
      var sel = t[0] === st.setTab;
      tabs.appendChild(h("button", { type: "button", class: "setTab" + (sel ? " sel" : ""), role: "tab", "aria-selected": String(sel),
        onclick: function () { if (!sel) { st.setTab = t[0]; render(); } } }, h("span", { class: "labelLarge" }, S(t[1]))));
    });
    root.appendChild(tabs);
    var page = h("div", { class: "setPage" });
    ({ RECORDING: recordingPage, AI: aiPage, LOOK: lookPage, ABOUT: aboutPage })[st.setTab](page);
    var body = h("div", { class: "setBody scroll grow", "data-scroll": "set-" + st.setTab }, page);
    // One page at a time: the old one fades out first, only then the new one fades in.
    var prevTab = was("setTab", st.setTab);
    if (prevTab != null && prevTab !== st.setTab && !still()) {
      after(function (r, old) {
        var o = old && old.querySelector(".setBody");
        if (o) {
          var ghost = o.firstChild.cloneNode(true);
          ghost.style.position = "absolute"; ghost.style.left = "0"; ghost.style.right = "0";
          ghost.style.top = (-o.scrollTop) + "px"; ghost.style.pointerEvents = "none";
          body.style.position = "relative";
          body.appendChild(ghost);
          ghost.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 100, fill: "forwards" }).onfinish = function () { ghost.remove(); };
        }
        page.animate([{ opacity: 0 }, { opacity: 0, offset: 100 / 240 }, { opacity: 1 }], { duration: 240 });
      });
    }
    root.appendChild(body);
    return root;
  }

  function named(el, name) { el.setAttribute("aria-label", name); return el; }
  function section(children) {
    return h("div", { class: "section" }, children);
  }
  function rule() { return h("div", { class: "hr" }); }

  function setting(key, title, hint, more, control) {
    var open = !!st.explain[key];
    var explains = hint != null || more != null;
    var el = h("div", { class: "setting" });
    var row = h("div", { class: "row" }, h("span", { class: "titleMedium grow" }, title));
    if (explains) {
      row.appendChild(h("button", { type: "button", class: "explain labelLarge", "aria-expanded": String(open), "aria-label": S("settings_explain_desc", title),
        onclick: function () { st.explain[key] = !open; render(); } },
        h("span", { class: "underline", style: { opacity: open ? 0 : 1 } }, S("settings_explain")),
        h("span", { class: "underline", style: { opacity: open ? 1 : 0 } }, S("settings_explain_hide"))));
    }
    el.appendChild(row);
    if (explains) {
      var f = fold("explain:" + key, open, function () {
        var c = h("div", { class: "col", style: { gap: "6px" } });
        if (hint != null) c.appendChild(ruled(hint, "bodySmall"));
        if (more != null) c.appendChild(ruled(more, "bodySmall"));
        return c;
      });
      if (f) el.appendChild(f);
    }
    // A slider or a row of options is read with the setting's name.
    if (/^(slider|radiogroup)$/.test(control.getAttribute("role") || "") && !control.hasAttribute("aria-label")) control.setAttribute("aria-label", title);
    el.appendChild(control);
    return el;
  }

  function boldWords(text, words) {
    var frag = document.createDocumentFragment(), rest = text;
    while (rest) {
      var best = -1, word = null;
      words.forEach(function (w) { var i = rest.indexOf(w); if (i >= 0 && (best < 0 || i < best)) { best = i; word = w; } });
      if (best < 0) { frag.appendChild(document.createTextNode(rest)); break; }
      frag.appendChild(document.createTextNode(rest.slice(0, best)));
      frag.appendChild(h("b", null, word));
      rest = rest.slice(best + word.length);
    }
    return frag;
  }

  function recordingPage(page) {
    var s = st.settings;
    var c = [];
    var onSilence = S("settings_recording_end_silence"), stopButton = S("settings_recording_end_stop");
    c.push(setting("end", S("settings_recording_end"), boldWords(S("settings_recording_end_hint", onSilence, stopButton), [onSilence, stopButton]), null,
      slide("end", ["SILENCE", "STOP_BUTTON"], s.recordingEnd, function (v) { return v === "SILENCE" ? onSilence : stopButton; }, function (v) { set({ recordingEnd: v }); })));
    c.push(ruled(S("settings_listening_unknown"), "bodySmall", "var(--onSurface)", { marginBottom: "10px" }));
    var f = fold("silence", s.recordingEnd === "SILENCE", function () {
      return h("div", { class: "col" }, rule(), setting("silence", S("settings_silence_length"), S("settings_silence_length_hint"), null,
        counter("silence", SILENCE, s.silenceSeconds, function (v) { return S("settings_seconds", v); }, function (v) { set({ silenceSeconds: v }); },
          function (v) { return v % 10 === 0 ? "MAJOR" : v % 5 === 0 ? "MINOR" : "NONE"; }, function (v) { return P("duration_seconds", v, v); })));
    });
    if (f) c.push(f);
    c.push(rule());
    var never = S("settings_pause_never");
    c.push(setting("pause", S("settings_pause"), S("settings_pause_hint"), null,
      counter("pause", PAUSE, s.pauseSeconds, function (v) { return v === 0 ? never : S("settings_seconds", v); }, function (v) { set({ pauseSeconds: v }); },
        function (v) { return v % 5 === 0 ? "MAJOR" : "MINOR"; }, function (v) { return v === 0 ? never : P("duration_seconds", v, v); })));
    c.push(rule());
    c.push(setting("max", S("settings_max_length"), S("settings_max_length_hint"), null,
      counter("max", MAX_RECORDING, s.maxRecordingSeconds, function (v) { return v < 60 ? S("settings_seconds", v) : S("settings_minutes", v / 60); },
        function (v) { set({ maxRecordingSeconds: v }); },
        function (v) { return v % 900 === 0 ? "MAJOR" : v % 300 === 0 ? "MINOR" : "NONE"; },
        function (v) { return v < 60 ? P("duration_seconds", v, v) : P("duration_minutes", v / 60, v / 60); })));
    c.push(rule());
    c.push(setting("speech", S("settings_speech_language"), S("settings_speech_language_hint"), null,
      slide("speech", ["pl-PL", "en-US"], s.speechLanguage, function (v) { return v.indexOf("en") === 0 ? S("speech_language_english") : S("speech_language_polish"); },
        function (v) { set({ speechLanguage: v }); })));
    c.push(rule());
    c.push(setting("online", S("settings_online_speech"), S("settings_online_speech_hint"), S("settings_online_speech_more"),
      slide("online", ["ASK", "ALWAYS", "NEVER"], s.onlineSpeech, function (v) { return S("settings_online_speech_" + v.toLowerCase()); }, function (v) { set({ onlineSpeech: v }); })));
    c.push(rule());
    c.push(ruled(S("settings_whisper_moved"), "bodySmall", null, { marginTop: "10px" }));
    c.push(pbtn(S("settings_whisper_open_ai"), function () { st.setTab = "AI"; render(); }, "", { style: { margin: "8px 0 10px" } }));
    page.appendChild(section(c));
  }

  function lookPage(page) {
    var s = st.settings;
    var c = [];
    c.push(setting("size", S("settings_text_size"), S("settings_text_size_hint"), null,
      slide("size", TEXT_SCALES, s.textScale, function (v) { return S("settings_percent", Math.round(v * 100)); }, function (v) { set({ textScale: v }); })));
    c.push(rule());
    c.push(setting("theme", S("settings_theme"), S("settings_theme_hint"), null,
      slide("theme", ["SYSTEM", "LIGHT", "DARK"], s.theme, function (v) { return S("settings_theme_" + v.toLowerCase()); }, function (v) { set({ theme: v }); })));
    c.push(rule());
    c.push(setting("lang", S("settings_app_language"), null, null,
      slide("lang", ["pl", "en"], st.lang, function (v) { return v === "pl" ? S("language_polish") : S("language_english"); },
        function (v) { st.lang = v; render(); })));
    c.push(rule());
    c.push(setting("motion", S("settings_reduce_motion"), S("settings_reduce_motion_hint"), null,
      lever("motion", s.reduceMotion, function (v) { set({ reduceMotion: v }); }, s.reduceMotion ? S("settings_on") : S("settings_off"), null, S("settings_reduce_motion"))));
    c.push(rule());
    c.push(setting("tilt", S("settings_tilted"), S("settings_tilted_hint"), null,
      lever("tilt", s.tiltedPaper, function (v) { set({ tiltedPaper: v }); }, s.tiltedPaper ? S("settings_on") : S("settings_off"), null, S("settings_tilted"))));
    page.appendChild(section(c));
  }

  function aboutPage(page) {
    var f = h("div", { class: "col", style: { padding: "0 4px" } },
      h("p", { class: "bodyMedium", style: { marginInline: 0 } }, S("settings_about_what")),
      h("p", { class: "bodySmall variant", style: { marginTop: "calc(8px - var(--trim))", marginInline: 0 } }, S("settings_footer")),
      pbtn(S("settings_first_steps"), function () { st.tour = 0; go("home"); }, "", { style: { marginTop: "12px" } }),
      pbtn(D("manual") + " ↗", function () { window.open(MANUAL_URL, "_blank", "noopener"); }, "", { style: { marginTop: "12px" } }),
      pbtn(S("settings_privacy_policy") + " ↗", function () { window.open(PRIVACY_URL, "_blank", "noopener"); }, "", { style: { marginTop: "12px" } }),
      h("p", { class: "labelMedium variant", style: { margin: "12px 0 0" } }, S("settings_version", VERSION)),
      pbtn(S("settings_licenses"), function () { go("licenses"); }, "", { style: { margin: "12px 0 4px" } }));
    page.appendChild(section(h("div", { style: { padding: "10px 0" } }, f)));
  }

  function aiPage(page) {
    var s = st.settings;
    var c = [];
    c.push(h("div", { class: "col", style: { padding: "10px 0", gap: "8px" } },
      ruled(S("ai_intro"), "bodySmall", "var(--onSurface)"), ruled(S("ai_queue"), "bodySmall")));
    c.push(h("div", { class: "col", style: { gap: "12px" } }, PROVIDERS.map(providerCard)));
    c.push(h("div", { class: "col", style: { padding: "16px 0 10px", gap: "8px" } },
      h("span", { class: "titleMedium" }, S("ai_limit")),
      named(counter("ailimit", AI_LIMITS, s.aiDailyLimit, function (v) { return String(v); }, function (v) { set({ aiDailyLimit: v }); }), S("ai_limit")),
      ruled(S("ai_limit_hint"), "bodySmall"),
      ruled(S("ai_privacy"), "bodySmall")));
    page.appendChild(section(c));

    var w = s.whisper;
    var wc = [setting("whisper", S("settings_whisper"), S("settings_whisper_hint"), S("settings_whisper_more"),
      slide("whisper", [null, "GROQ", "OPENAI"], w, function (v) { return v == null ? S("settings_whisper_off") : providerName(v); }, function (v) { set({ whisper: v }); }))];
    var lastW = was("whisperShown", w) || w;
    var wf = fold("whisper", w != null, function () {
      var name = providerName(w || lastW);
      return h("div", { class: "col", style: { paddingBottom: "10px", gap: "6px" } },
        ruled(S("settings_whisper_data", name), "bodySmall", "var(--onSurface)"),
        ruled(S("settings_whisper_key_missing", name), "bodySmall"));
    });
    if (wf) wc.push(wf);
    page.appendChild(section(wc));
  }

  function providerCard(p) {
    var a = st.ai[p.id];
    var on = !!st.settings.aiProviders[p.id];
    var name = providerName(p.id);
    var head = h("div", { class: "row" },
      h("button", { type: "button", class: "col grow", style: { minHeight: "48px", justifyContent: "center", alignItems: "flex-start" }, "aria-expanded": String(a.open),
        onclick: function () { a.open = !a.open; render(); } },
        h("h3", { class: "titleMedium", style: { margin: 0 } }, name),
        foldLabel(S(a.open ? "ai_fold" : "ai_unfold"), a.open, "prov" + p.id)),
      lever("ai" + p.id, on, function (v) { st.settings.aiProviders[p.id] = v; render(); }, on ? S("ai_on") : S("ai_off"), [S("ai_on"), S("ai_off")], name));
    var lead = p.free ? document.createTextNode(S("ai_free")) : h("b", null, S("ai_paid"));
    var c = [head, h("p", { class: "bodySmall variant", style: { marginInline: 0 } }, lead, " - " + S("ai_p_" + p.t + "_hint"))];
    var wf = fold("aiwarn" + p.id, on, function () { return warning(S("ai_no_key")); });
    if (wf) c.push(wf);
    var bf = fold("aibody" + p.id, a.open, function () { return providerBody(p, a); });
    if (bf) c.push(bf);
    return h("div", { class: "provider" + (on ? " on" : "") }, c);
  }

  function providerBody(p, a) {
    var s = st.settings;
    var c = h("div", { class: "col", style: { paddingTop: "6px", gap: "14px" } });
    function block(title, text) { return h("div", { class: "col", style: { gap: "4px" } }, h("span", { class: "titleSmall" }, title), h("span", { class: "bodySmall" }, text)); }
    c.appendChild(block(S("ai_what_title"), S("ai_" + p.t + "_what")));
    c.appendChild(block(S("ai_privacy_title"), S("ai_" + p.t + "_privacy")));
    // How to get a key - unfolded while there is no key.
    var steps = h("div", { class: "col", style: { gap: "8px" } },
      h("button", { type: "button", style: { minHeight: "48px", padding: "12px 0", alignSelf: "flex-start" }, "aria-expanded": String(a.stepsOpen),
        onclick: function () { a.stepsOpen = !a.stepsOpen; render(); } }, foldLabel(S("ai_how_key"), a.stepsOpen, "steps" + p.id)));
    var sf = fold("steps" + p.id, a.stepsOpen, function () {
      var l = h("div", { class: "col", style: { gap: "8px" } },
        pbtn(S("ai_get_key") + " ↗", function () { window.open(p.key, "_blank", "noopener"); }));
      [1, 2, 3].forEach(function (i) {
        l.appendChild(h("div", { class: "row step" }, h("span", { class: "num labelMedium" }, String(i)),
          h("span", { class: "bodySmall grow", style: { paddingTop: "3px" } }, S("ai_how_" + p.t + "_" + i))));
      });
      return l;
    });
    if (sf) steps.appendChild(sf);
    c.appendChild(steps);
    // The key: typed here, never saved in the demo.
    var save = pbtn(S("ai_key_save"), demoOff, "filled", { disabled: a.draft.length < 8 });
    var keyInput = h("input", { type: "password", class: "bodyLarge", "aria-label": S("ai_key"), value: a.draft, autocomplete: "off", spellcheck: "false" });
    keyInput.addEventListener("input", function () { a.draft = keyInput.value; save.setAttribute("aria-disabled", a.draft.length < 8 ? "true" : "false"); });
    c.appendChild(h("div", { class: "col", style: { gap: "6px" } },
      h("span", { class: "labelLarge variant", style: { minHeight: "48px", display: "flex", alignItems: "center" } }, S("ai_key")),
      h("div", { class: "lineField" }, keyInput),
      h("span", { class: "bodySmall variant" }, S("ai_key_not_set")),
      h("div", { class: "flow" }, save),
      h("span", { class: "bodySmall variant" }, S("ai_key_hint"))));
    // Model: saved when the field is left; empty = the provider's default.
    var saved = s.aiModels[p.id] || "";
    var model = h("input", { type: "text", class: "bodyLarge", "aria-label": S("ai_model"), placeholder: p.model, value: saved, autocomplete: "off", spellcheck: "false" });
    model.addEventListener("keydown", function (e) { if (e.key === "Enter") model.blur(); });
    model.addEventListener("change", function () {
      var v = model.value.trim();
      if (v !== (s.aiModels[p.id] || "")) { s.aiModels[p.id] = v; flashSaved(a); }
    });
    var mc = h("div", { class: "col", style: { gap: "4px" } }, h("span", { class: "labelLarge variant" }, S("ai_model")), h("div", { class: "lineField" }, model));
    var msf = fold("modelSaved" + p.id, a.modelSaved, function () { return h("span", { class: "bodySmall", role: "status" }, S("ai_model_saved")); });
    if (msf) mc.appendChild(msf);
    mc.appendChild(h("span", { class: "bodySmall variant" }, S("ai_model_hint", p.model)));
    c.appendChild(mc);
    if (p.free) c.appendChild(modelPicker(p, a));
    c.appendChild(h("div", { class: "col", style: { gap: "6px" } },
      pbtn(S("ai_test"), demoOff, "", { disabled: true }),
      h("span", { class: "bodySmall variant" }, S("ai_test_hint"))));
    c.appendChild(h("span", { class: "bodySmall variant" }, S("ai_usage", 0, s.aiDailyLimit, 0)));
    if (p.id === "ANTHROPIC") c.appendChild(topUp());
    return c;
  }

  var savedTimers = {};
  function flashSaved(a, key) {
    a.modelSaved = true;
    renderAfterPress();
    var k = (key || "m") + (PROVIDERS.filter(function (p) { return st.ai[p.id] === a; })[0] || {}).id;
    clearTimeout(savedTimers[k]);
    savedTimers[k] = setTimeout(function () { a.modelSaved = false; if (st.screen === "settings") render(); }, 3000);
  }

  // "Show free models": without the internet the app offers the list saved in it - the demo does the same.
  function modelPicker(p, a) {
    var current = (st.settings.aiModels[p.id] || "").trim() || p.model;
    var c = h("div", { class: "col", style: { gap: "8px" } }, pbtn(S("ai_free_models_fetch"), function () { a.models = true; render(); }));
    var f = fold("models" + p.id, a.models, function () {
      var flow = h("div", { class: "flow" });
      p.saved.forEach(function (m) {
        var picked = m === current;
        flow.appendChild(h("button", { type: "button", class: "modelTape" + (picked ? " sel" : ""), role: "radio", "aria-checked": String(picked),
          onclick: function () { st.settings.aiModels[p.id] = m; render(); } }, h("span", { class: "labelLarge" }, (picked ? "✓ " : "") + m)));
      });
      return h("div", { class: "col", style: { gap: "8px" } },
        h("span", { class: "bodySmall variant" }, S("ai_free_models_saved")),
        h("span", { class: "bodySmall" }, S("ai_free_models")), flow);
    });
    if (f) c.appendChild(f);
    return c;
  }

  function topUp() {
    var s = st.settings;
    var dec = function (v) { return st.lang === "pl" ? v.toFixed(2).replace(".", ",") : v.toFixed(2); };
    var input = h("input", { type: "text", inputmode: "decimal", class: "bodyLarge", "aria-label": S("ai_topup"), placeholder: dec(5),
      value: s.topUpCents != null ? dec(s.topUpCents / 100) : "", autocomplete: "off" });
    var wrong = h("div");
    input.addEventListener("keydown", function (e) { if (e.key === "Enter") input.blur(); });
    input.addEventListener("change", function () {
      // Same rules as ClaudeSpend.parse: dollars with at most two decimals, 0.01 to 10 000, "$" and "usd" allowed.
      var t = input.value.replace(/\$|usd/gi, "").replace(/\s/g, "").replace(",", ".");
      if (!t) { s.topUpCents = null; s.topUpDay = null; renderAfterPress(); return; }
      var m = /^(\d{1,6})(?:\.(\d{1,2}))?$/.exec(t);
      var cents = m ? Number(m[1]) * 100 + Number((m[2] || "").padEnd(2, "0")) : 0;
      if (cents < 1 || cents > 1000000) { wrong.textContent = ""; wrong.appendChild(warning(S("ai_topup_wrong"))); return; }
      s.topUpCents = cents; s.topUpDay = todayEpoch(); renderAfterPress();
    });
    input.addEventListener("input", function () { wrong.textContent = ""; });
    var hint = s.topUpCents != null
      ? S("ai_topup_pln", dec(s.topUpCents / 100 * USD_PLN), new Intl.DateTimeFormat(locale(), { dateStyle: "medium", timeZone: "UTC" }).format(utcDate(s.topUpDay)))
      : S("ai_topup_hint");
    return h("div", { class: "col", style: { gap: "4px" } },
      h("span", { class: "labelLarge variant" }, S("ai_topup")), h("div", { class: "lineField" }, input), wrong,
      h("span", { class: "bodySmall variant" }, hint));
  }

  function buildLicenses() {
    var text = window.TAPEDO_LICENSES.replace(/([a-z,;])\n(?=[a-z("])/g, "$1 ");
    return h("div", { class: "screen-licenses" },
      header(S("licenses_title"), function () { go("settings"); }),
      h("div", { class: "scroll grow", "data-scroll": "licenses" },
        h("div", { style: { padding: "8px 16px" } },
          h("p", { class: "bodyMedium", style: { marginInline: 0, padding: "8px 4px" } }, S("licenses_intro")),
          h("div", { class: "bodySmall pre licenseText" }, text))));
  }

  // ---------- Screens and transitions (ui/TapedoNavHost.kt) ----------

  function go(screen) {
    hideBubble();
    st.screen = screen;
    render();
  }

  var app, current = null, currentScreen = null;

  function isDark() {
    var t = st.settings.theme;
    return t === "DARK" || (t === "SYSTEM" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  }

  function applyTheme() {
    var dark = isDark();
    app.classList.toggle("dark", dark);
    app.classList.toggle("still", still());
    app.style.setProperty("--ts", st.settings.textScale);
    typeVars();
    document.body.classList.toggle("paper", !dark);
    document.documentElement.lang = st.lang;
    document.title = D("title");
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", dark ? "#1C1B19" : "#F3ECDF");
  }

  // Text metrics as Compose lays them out on the phone: line heights rounded up to whole pixels, the typewriter
  // face with whole-pixel advances (0.6 em), and for the body styles the leading trimmed above the first line
  // and below the last; the rule of ruled text sits half-way between the baseline and the line's bottom.
  var STYLES = { displaySmall: 44, headlineMedium: 36, headlineSmall: 32, titleLarge: 28, titleMedium: 24, titleSmall: 20,
    labelLarge: 20, labelMedium: 16, labelSmall: 16 };
  var BODY = { bodyLarge: [17, 25], bodyMedium: [15, 22], bodySmall: [13, 18] };
  var bodyStep = {};
  function typeVars() {
    var dpr = window.devicePixelRatio || 1, ts = st.settings.textScale;
    function lh(l) { return Math.ceil(l * ts * dpr - 0.001) / dpr; }
    Object.keys(STYLES).forEach(function (k) { app.style.setProperty("--lh-" + k, lh(STYLES[k]) + "px"); });
    Object.keys(BODY).forEach(function (k) {
      var size = BODY[k][0] * ts, l = lh(BODY[k][1]);
      var fh = 1.123046875 * size, asc = 0.78125 * size, desc = 0.341796875 * size, extra = l - fh;
      var trim = extra / 2;
      var adv = 0.6 * size * dpr;
      app.style.setProperty("--lh-" + k, l + "px");
      app.style.setProperty("--trim-" + k, trim.toFixed(3) + "px");
      app.style.setProperty("--ry-" + k, (trim + asc + (desc + extra * 0.3043) / 2).toFixed(3) + "px");
      app.style.setProperty("--ls-" + k, ((Math.round(adv) - adv) / dpr).toFixed(3) + "px");
      bodyStep[k] = { lh: l, first: fh + extra * 0.3043 };
    });
  }

  function buildScreen() {
    if (st.screen === "card" && st.editing) return buildCard();
    if (st.screen === "settings") return buildSettings();
    if (st.screen === "licenses") return buildLicenses();
    st.screen = "home";
    return buildHome();
  }

  // Removing a field that holds unsaved typing fires its "change" in the middle of a render, and that change
  // asks for a render of its own - it waits until this one is done instead of tearing it apart.
  var rendering = false, renderAgain = false;
  function render() {
    if (rendering) { renderAgain = true; return; }
    rendering = true;
    try { renderNow(); } finally { rendering = false; }
    if (renderAgain) { renderAgain = false; render(); }
  }

  function renderNow() {
    applyTheme();
    nextMemo = new Map();
    hooks = [];
    var old = current;
    var same = old && currentScreen === st.screen;
    // Where things were, so cards that moved glide to their new place (LazyColumn animateItem).
    var rects = {}, scrolls = {}, focus = null;
    var a = document.activeElement;
    // A keyboard user's place: focus was on the old screen, or nowhere after the focused key vanished.
    var hadFocus = !!old && !!a && (old.contains(a) || a === document.body);
    if (same) {
      old.querySelectorAll("[data-flip]").forEach(function (n) { rects[n.getAttribute("data-flip")] = n.getBoundingClientRect().top; });
      old.querySelectorAll("[data-scroll]").forEach(function (n) { scrolls[n.getAttribute("data-scroll")] = [n.scrollTop, n.scrollLeft]; });
      if (a && old.contains(a)) {
        focus = { key: a.getAttribute("data-focus"), label: a.getAttribute("aria-label"), role: a.getAttribute("role"), cls: a.className,
          tag: a.tagName, text: a.textContent, idx: Array.prototype.indexOf.call(old.querySelectorAll(FOCUSABLE), a),
          sel: "selectionStart" in a && a.type !== "number" ? [a.selectionStart, a.selectionEnd] : null };
      }
    }
    if (!same && old && currentScreen === "home") {
      var ol = old.querySelector('[data-scroll="list"]');
      homeScroll = ol ? ol.scrollTop : 0;
    }
    var el = buildScreen();
    el.classList.add("layer");
    if (same) {
      app.replaceChild(el, old);
      el.querySelectorAll("[data-scroll]").forEach(function (n) {
        var s = scrolls[n.getAttribute("data-scroll")];
        if (s) { n.scrollTop = s[0]; n.scrollLeft = s[1]; }
      });
      var wanted = pendingFocus && hadFocus && el.querySelector(pendingFocus);
      if (wanted) wanted.focus({ preventScroll: true });
      else if (focus) restoreFocus(el, focus);
    } else {
      transition(old, el, currentScreen, st.screen);
      // Back from a card the list stands where it was (CardStack.kt keeps its scroll state outside).
      var nl = st.screen === "home" && el.querySelector('[data-scroll="list"]');
      if (nl) nl.scrollTop = homeScroll;
      if (hadFocus && !touring()) arrivalFocus(el, currentScreen);
    }
    pendingFocus = null;
    current = el;
    currentScreen = st.screen;
    syncHistory();
    memo = nextMemo;
    var run = hooks;
    run.forEach(function (f) { f(el, old); });
    if (same && !still()) {
      el.querySelectorAll("[data-flip]").forEach(function (n) {
        var k = n.getAttribute("data-flip");
        if (k in rects) {
          var dy = rects[k] - n.getBoundingClientRect().top;
          if (Math.abs(dy) > 1) n.animate([{ transform: "translateY(" + dy + "px)" }, { transform: "none" }], { duration: 260, easing: "cubic-bezier(.3,1.1,.5,1)" });
        } else {
          n.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 260 });
        }
      });
    }
  }

  var FOCUSABLE = "button, [role=button], input, textarea, [tabindex]";
  // Where the keyboard goes after the next render when the focused key goes away with it (a deleted card's x).
  var pendingFocus = null;
  // The same key after a re-render: by its focus key, its label, its words, and last by its place in the
  // order - a picked option or a moved slider changes its class or words, and a removed key hands over to the next.
  // The browser's Back (Alt+Left, the mouse's side key, the phone's back gesture) works like the app's "Back":
  // one history entry stands for "away from the home screen", so Back closes the card or Settings first.
  var homeScroll = 0, inHistory = false, skipPop = false;
  function syncHistory() {
    var away = st.screen !== "home";
    try {
      if (away && !inHistory) { history.pushState({ tapedo: 1 }, ""); inHistory = true; }
      else if (!away && inHistory) { inHistory = false; skipPop = true; history.back(); }
    } catch (e) { /* no history in this frame */ }
  }
  window.addEventListener("popstate", function () {
    if (skipPop) { skipPop = false; return; }
    inHistory = false;
    hideBubble();
    if (st.screen !== "home") back();
  });

  function restoreFocus(el, f) {
    var t = null;
    if (f.key) t = el.querySelector('[data-focus="' + CSS.escape(f.key) + '"]');
    var all = Array.prototype.filter.call(el.querySelectorAll(FOCUSABLE), function (n) { return !n.closest("[inert]"); });
    // Of several equal keys (the key field of each open provider) the one nearest to the old place.
    function find(test) {
      var best = null;
      for (var i = 0; i < all.length; i++) if (test(all[i]) && (!best || Math.abs(i - f.idx) < Math.abs(best[1] - f.idx))) best = [all[i], i];
      return best && best[0];
    }
    function role(n) { return n.getAttribute("role") === f.role && n.tagName === f.tag; }
    if (!t && f.label) t = find(function (n) { return n.getAttribute("aria-label") === f.label && n.className === f.cls; })
      || find(function (n) { return n.getAttribute("aria-label") === f.label && role(n); });
    if (!t && !f.label && f.text) t = find(function (n) { return role(n) && n.textContent === f.text; });
    if (!t && f.idx >= 0 && all.length) t = all[Math.min(f.idx, all.length - 1)];
    if (!t) return;
    t.focus({ preventScroll: true });
    // A field rebuilt while typing keeps the caret where it was.
    if (f.sel && t.tagName === f.tag && "setSelectionRange" in t) { try { t.setSelectionRange(f.sel[0], f.sel[1]); } catch (e) { /* not a text field */ } }
  }

  // After a screen change the keyboard lands where the eye goes: back on the card or the gear it came from,
  // otherwise on the new screen's back arrow.
  function arrivalFocus(el, from) {
    var t = null;
    if (st.screen === "home") {
      if (from === "card") t = (lastCardId != null && el.querySelector('[data-flip="n' + lastCardId + '"] .paper')) || el.querySelector("[data-strip] .msg button");
      else t = el.querySelector('[data-tour="SETTINGS"]');
      t = t || el.querySelector(".tab.sel");
    } else if (st.screen === "settings" && from === "licenses") {
      t = Array.prototype.find.call(el.querySelectorAll(".pbtn"), function (b) { return b.textContent === S("settings_licenses"); });
    }
    t = t || el.querySelector(".header button");
    if (t) t.focus({ preventScroll: true });
  }

  function transition(old, el, from, to) {
    var s = still();
    var up = (from === "home" && to === "settings") || (from === "settings" && to === "licenses");
    var down = (from === "settings" && to === "home") || (from === "licenses" && to === "settings");
    if (!old) { app.appendChild(el); return; }
    old.style.pointerEvents = "none";
    old.setAttribute("aria-hidden", "true");
    old.inert = true; // no Tab into the screen that is fading out
    if (s) { app.appendChild(el); old.remove(); return; }
    if (up) {
      app.appendChild(el);
      el.animate([{ transform: "translateY(100%)" }, { transform: "none" }], { duration: 520, easing: "cubic-bezier(.25,1.2,.5,1)" });
      el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 300 });
      old.animate([{ opacity: 1 }, { opacity: .6 }], { duration: 300, fill: "forwards" }).onfinish = function () { old.remove(); };
    } else if (down) {
      app.insertBefore(el, old);
      el.animate([{ opacity: .6 }, { opacity: 1 }], { duration: 300 });
      old.animate([{ transform: "none", opacity: 1 }, { transform: "translateY(100%)", opacity: 0 }], { duration: 220, fill: "forwards" }).onfinish = function () { old.remove(); };
    } else {
      app.appendChild(el);
      el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 220 });
      old.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 220, fill: "forwards" }).onfinish = function () { old.remove(); };
    }
  }

  // "Back" (Esc on a computer): the card view and Settings close; on the home screen it clears the search.
  function back() {
    if (bubbleEl) { hideBubble(); return; }
    if (st.screen === "card") closeCard();
    else if (st.screen === "licenses") go("settings");
    else if (st.screen === "settings") go("home");
    else if (st.query) { st.query = ""; render(); }
  }

  function start() {
    app = document.getElementById("app");
    document.addEventListener("keydown", function (e) {
      if (touring()) tourKey(e);
      else if (e.key === "Escape") back();
    });
    // A plain mouse wheel only turns up and down: over a row that scrolls sideways (the type row), it moves
    // the row until its end, then the page scrolls on as usual.
    app.addEventListener("wheel", function (e) {
      if (e.ctrlKey || e.shiftKey || Math.abs(e.deltaX) >= Math.abs(e.deltaY)) return;
      var row = e.target.closest && e.target.closest(".hscroll");
      if (!row) return;
      var max = row.scrollWidth - row.clientWidth;
      if (max <= 0 || (e.deltaY < 0 && row.scrollLeft <= 0) || (e.deltaY > 0 && row.scrollLeft >= max - 1)) return;
      e.preventDefault();
      row.scrollLeft += e.deltaY * (e.deltaMode === 1 ? 16 : 1);
    }, { passive: false });
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", render);
    window.matchMedia("(prefers-reduced-motion: reduce)").addEventListener("change", render);
    // A double click is one press: the second click would land on whatever slid under the pointer (the next
    // item's x, the hour row after the date was removed) - the app keeps a half-second guard the same way.
    app.addEventListener("click", function (e) {
      if (e.detail > 1 && e.target.closest("button, [role=button], [role=checkbox]") && !e.target.closest("input, textarea")) {
        e.stopPropagation(); e.preventDefault();
      }
    }, true);
    // The demo bubble points at its key - when the page scrolls it would point at something else.
    document.addEventListener("scroll", function () { if (bubbleEl) hideBubble(); }, true);
    var resizeTimer = 0;
    window.addEventListener("resize", function () { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 150); });
    render();
  }

  // The fonts first, so the ruled lines fit the text - but a font load that never settles must not leave
  // the page blank: after 3 s the demo starts with whatever is there and re-renders when the fonts arrive.
  var started = false;
  function startOnce() { if (!started) { started = true; start(); } else render(); }
  if (document.fonts && document.fonts.load) {
    Promise.all([document.fonts.load("16px Bricolage"), document.fonts.load("16px 'Courier Prime'"), document.fonts.load("bold 16px 'Courier Prime'")])
      .then(startOnce, startOnce);
    setTimeout(function () { if (!started) { started = true; start(); } }, 3000);
  } else start();
})();
