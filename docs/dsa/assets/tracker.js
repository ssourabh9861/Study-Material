/* DSA Tracker app — vanilla JS, no build step. Loads JSON content + live/fallback solved-state. */
(function () {
  "use strict";

  // data dir derived from this script's URL so it works under any base path
  var SCRIPT_SRC = (document.currentScript && document.currentScript.src) || "";
  var BASE = SCRIPT_SRC.replace(/assets\/tracker\.js.*$/, "");
  var DATA = BASE + "data/";
  var SHEET_CSV_URL = (window.DSA_SHEET_CSV_URL || "").trim(); // set in index.md once sheet is published

  var LS_LIVE = "dsa_solved_live";
  var LS_TIME = "dsa_solved_synced_at";
  var LS_VIEW = "dsa_view";
  var LS_OPEN = "dsa_open_topics";

  var state = {
    index: [],
    solved: {},                 // id -> true (merged best-known)
    contentCache: {},           // topicSlug -> {id: entry}
    lc: {},                     // id -> LeetCode url
    openTopics: loadSet(LS_OPEN),
    search: "", difficulty: "all",
    view: localStorage.getItem(LS_VIEW) || "solved",   // solved | unsolved | all
    root: null,
  };

  function loadSet(k) { try { return new Set(JSON.parse(localStorage.getItem(k) || "[]")); } catch (e) { return new Set(); } }
  function saveSet(k, s) { localStorage.setItem(k, JSON.stringify(Array.from(s))); }
  function slug(s) { return String(s).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, ""); }
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]; }); }
  function inlineMd(s) {
    return esc(s)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*]+)\*/g, "$1<em>$2</em>");
  }
  function el(html) { var d = document.createElement("div"); d.innerHTML = html.trim(); return d.firstChild; }
  function diffClass(d) { return "pill-" + slug(d); }

  // ---------- CSV parsing (handles quotes) ----------
  function parseCSV(text) {
    var rows = [], row = [], cur = "", q = false;
    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (q) {
        if (c === '"') { if (text[i + 1] === '"') { cur += '"'; i++; } else q = false; }
        else cur += c;
      } else {
        if (c === '"') q = true;
        else if (c === ",") { row.push(cur); cur = ""; }
        else if (c === "\n") { row.push(cur); rows.push(row); row = []; cur = ""; }
        else if (c === "\r") { /* skip */ }
        else cur += c;
      }
    }
    if (cur.length || row.length) { row.push(cur); rows.push(row); }
    return rows;
  }
  // Parse the tracker sheet CSV (positional: topic,subtopic,problem,difficulty,solved,notes). Returns {id:true}.
  function solvedFromCSV(text) {
    var rows = parseCSV(text), out = {};
    for (var i = 1; i < rows.length; i++) { // skip header
      var r = rows[i];
      if (!r || r.length < 5) continue;
      var topic = (r[0] || "").trim(), title = (r[2] || "").trim();
      var val = (r[4] || "").trim().toUpperCase();
      if (!topic || !title) continue;
      if (val === "TRUE" || val === "1" || val === "YES") out[slug(topic) + "__" + slug(title)] = true;
    }
    return out;
  }

  // ---------- data loading ----------
  var VER = (window.DSA_ASSET_VER || "0");
  function fetchJSON(url) {
    var u = url + (url.indexOf("?") < 0 ? "?" : "&") + "v=" + VER;
    return fetch(u, { cache: "no-cache" }).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); });
  }

  function loadTopic(topicSlug) {
    if (state.contentCache[topicSlug]) return Promise.resolve(state.contentCache[topicSlug]);
    return fetchJSON(DATA + "topics/" + topicSlug + ".json")
      .then(function (j) { state.contentCache[topicSlug] = j; return j; })
      .catch(function () { state.contentCache[topicSlug] = {}; return {}; });
  }

  function syncLive(force) {
    if (!SHEET_CSV_URL) return Promise.resolve(false);
    var btn = document.getElementById("dsa-sync-icon");
    if (btn) btn.classList.add("dsa-spin");
    return fetch(SHEET_CSV_URL + (force ? "&_=" + Date.now() : ""), { cache: "no-cache" })
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.text(); })
      .then(function (text) {
        var live = solvedFromCSV(text);
        if (Object.keys(live).length === 0) throw new Error("empty");
        state.solved = live;
        localStorage.setItem(LS_LIVE, JSON.stringify(live));
        localStorage.setItem(LS_TIME, String(Date.now()));
        renderAll();
        return true;
      })
      .catch(function () { markSyncTime(true); return false; })
      .finally(function () { if (btn) btn.classList.remove("dsa-spin"); });
  }

  function markSyncTime(failed) {
    var e = document.getElementById("dsa-sync-time"); if (!e) return;
    if (!SHEET_CSV_URL) { e.textContent = "snapshot (live sync not configured)"; return; }
    var t = Number(localStorage.getItem(LS_TIME) || 0);
    if (failed && !t) { e.textContent = "using snapshot — live sync unreachable"; return; }
    e.textContent = t ? "synced " + timeAgo(t) : "";
  }
  function timeAgo(t) {
    var s = Math.floor((Date.now() - t) / 1000);
    if (s < 60) return "just now";
    if (s < 3600) return Math.floor(s / 60) + "m ago";
    if (s < 86400) return Math.floor(s / 3600) + "h ago";
    return Math.floor(s / 86400) + "d ago";
  }

  // ---------- rendering ----------
  function isSolved(id) { return !!state.solved[id]; }

  function passesFilter(m) {
    if (state.search && m.title.toLowerCase().indexOf(state.search) === -1) return false;
    if (state.difficulty !== "all" && m.difficulty !== state.difficulty) return false;
    return true;
  }
  function visible(m) {
    if (!passesFilter(m)) return false;
    var solved = isSolved(m.id);
    if (state.view === "solved") return solved;
    if (state.view === "unsolved") return !solved;
    return true; // "all"
  }

  function renderAll() {
    renderDashboard();
    renderToolbarState();
    renderTopics();
    markSyncTime(false);
  }

  function renderDashboard() {
    var total = state.index.length, solved = 0;
    var byDiff = {}; // diff -> [solved,total]
    state.index.forEach(function (m) {
      var s = isSolved(m.id);
      if (s) solved++;
      var d = byDiff[m.difficulty] || [0, 0]; d[1]++; if (s) d[0]++; byDiff[m.difficulty] = d;
    });
    var pct = total ? solved / total : 0;
    var C = 2 * Math.PI * 56, off = C * (1 - pct);
    var order = ["Medium", "Hard", "Very Hard"];
    var colors = { "Medium": "var(--medium)", "Hard": "var(--hard)", "Very Hard": "var(--veryhard)" };
    var bars = order.filter(function (d) { return byDiff[d]; }).map(function (d) {
      var p = byDiff[d], w = p[1] ? (p[0] / p[1] * 100) : 0;
      return '<div class="dsa-diffbar"><span class="name">' + d + '</span>' +
        '<span class="dsa-bar"><i style="width:' + w.toFixed(1) + '%;background:' + colors[d] + '"></i></span>' +
        '<span class="count">' + p[0] + " / " + p[1] + "</span></div>";
    }).join("");
    var dash = state.root.querySelector(".dsa-dash");
    dash.innerHTML =
      '<div class="dsa-ring"><svg width="128" height="128" viewBox="0 0 128 128">' +
      '<circle class="track" cx="64" cy="64" r="56" fill="none" stroke-width="12"/>' +
      '<circle class="prog" cx="64" cy="64" r="56" fill="none" stroke-width="12" ' +
      'stroke-dasharray="' + C.toFixed(1) + '" stroke-dashoffset="' + off.toFixed(1) + '"/></svg>' +
      '<div class="label"><b>' + Math.round(pct * 100) + '%</b><span>' + solved + " / " + total + "</span></div></div>" +
      '<div class="dsa-diffbars">' + bars + "</div>";
  }

  function renderToolbarState() {
    state.root.querySelectorAll(".dsa-seg [data-view]").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-view") === state.view);
    });
  }

  function renderTopics() {
    var container = state.root.querySelector(".dsa-topics");
    container.innerHTML = "";
    // group index by topic (preserve order), then subtopic
    var topics = [], byTopic = {};
    state.index.forEach(function (m) {
      if (!byTopic[m.topicSlug]) { byTopic[m.topicSlug] = { name: m.topic, slug: m.topicSlug, subs: {}, order: [] }; topics.push(byTopic[m.topicSlug]); }
      var T = byTopic[m.topicSlug];
      if (!T.subs[m.subtopic]) { T.subs[m.subtopic] = []; T.order.push(m.subtopic); }
      T.subs[m.subtopic].push(m);
    });

    var anyVisible = false;
    topics.forEach(function (T) {
      var all = T.order.reduce(function (a, s) { return a.concat(T.subs[s]); }, []);
      var solvedCnt = all.filter(function (m) { return isSolved(m.id); }).length;
      var vis = all.filter(visible);
      // hide whole topic when nothing matches the current view/filters
      if (vis.length === 0) return;
      anyVisible = true;

      var searching = !!(state.search || state.difficulty !== "all");
      var open = state.openTopics.has(T.slug) || searching;
      var w = all.length ? (solvedCnt / all.length * 100) : 0;
      var pct = Math.round(w);

      var sec = el('<section class="dsa-topic' + (open ? " open" : "") + '" data-topic="' + T.slug + '"></section>');
      sec.appendChild(el(
        '<div class="dsa-topic-head" data-toggle="' + T.slug + '">' +
        '<span class="chev">▶</span>' +
        '<span class="dsa-topic-title">' + esc(T.name) + "</span>" +
        '<span class="dsa-topic-meta">' +
        '<span class="dsa-bar"><i style="width:' + w.toFixed(1) + '%;background:var(--solved)"></i></span>' +
        '<span class="frac">' + solvedCnt + " / " + all.length + "</span></span></div>"));

      var body = el('<div class="dsa-topic-body"></div>');
      var hasMultiSub = T.order.length > 1 || (T.order[0] && T.order[0] !== T.name);
      T.order.forEach(function (subName) {
        var subVis = T.subs[subName].filter(visible);
        if (subVis.length === 0) return;
        if (hasMultiSub) body.appendChild(el('<div class="dsa-subhead">' + esc(subName) + "</div>"));
        subVis.forEach(function (m) { body.appendChild(renderCard(m)); });
      });
      sec.appendChild(body);
      container.appendChild(sec);
    });

    if (container.children.length === 0)
      container.appendChild(el('<div class="dsa-empty">No problems match the current filters.</div>'));
  }

  function renderCard(m) {
    var solved = isSolved(m.id);
    var card = el('<div class="dsa-card ' + (solved ? "solved" : "locked") + '" data-id="' + m.id + '"></div>');
    var badges = '<span class="dsa-pill ' + diffClass(m.difficulty) + '">' + esc(m.difficulty) + "</span>";
    if (m.notes) badges += '<span class="dsa-note">' + esc(m.notes) + "</span>";
    if (!m.hasContent) badges += '<span class="dsa-note" style="opacity:.7">content pending</span>';
    card.appendChild(el(
      '<div class="dsa-card-head">' +
      '<span class="status">' + (solved ? "✅" : "🔒") + "</span>" +
      '<span class="ptitle">' + esc(m.title) + "</span>" +
      '<span class="badges">' + badges + "</span></div>"));
    card.appendChild(el('<div class="dsa-card-body"><div class="dsa-pending">Loading…</div></div>'));
    return card;
  }

  function renderDetail(bodyEl, entry) {
    if (!entry) { bodyEl.innerHTML = '<div class="dsa-pending">Reference content not generated yet for this problem.</div>'; return; }
    var h = "";
    var pid = slug(entry.topic) + "__" + slug(entry.title);
    var lcTerm = (entry.title || "").replace(/[\/()]/g, " ").replace(/\s+/g, " ").trim();
    var lcUrl = (state.lc && state.lc[pid]) ||
                ("https://leetcode.com/problemset/?search=" + encodeURIComponent(lcTerm));
    h += '<a class="dsa-lc" href="' + lcUrl + '" target="_blank" rel="noopener">View on LeetCode ↗</a>';
    if (entry.pattern) h += '<p class="dsa-cmplx"><b>Pattern:</b> ' + inlineMd(entry.pattern) + "</p>";
    h += "<h4>Problem</h4><p>" + inlineMd(entry.statement || "") + "</p>";
    if (entry.examples && entry.examples.length) {
      h += "<h4>Example</h4>";
      entry.examples.forEach(function (ex) {
        var t = "Input:  " + (ex.input || "") + "\nOutput: " + (ex.output || "") + (ex.explanation ? "\n" + ex.explanation : "");
        h += '<div class="dsa-ex"><code>' + esc(t) + "</code></div>";
      });
    }
    if (entry.constraints && entry.constraints.length) {
      h += "<h4>Constraints</h4><ul class=\"dsa-constraints\">" +
        entry.constraints.map(function (c) { return "<li>" + esc(c) + "</li>"; }).join("") + "</ul>";
    }
    var apps = entry.approaches || [];
    if (apps.length) {
      h += "<h4>Solution" + (apps.length > 1 ? "s" : "") + "</h4>";
      if (apps.length > 1) {
        h += '<div class="dsa-tabs">' + apps.map(function (a, i) {
          return '<span class="dsa-tab' + (i === 0 ? " active" : "") + '" data-tab="' + i + '">' + esc(a.name || ("Approach " + (i + 1))) + "</span>";
        }).join("") + "</div>";
      }
      h += apps.map(function (a, i) {
        var body = "";
        if (apps.length === 1 && a.name) body += '<p class="dsa-cmplx"><b>' + esc(a.name) + "</b></p>";
        if (a.idea) body += "<p>" + inlineMd(a.idea) + "</p>";
        if (a.time || a.space) body += '<p class="dsa-cmplx"><b>Time:</b> ' + esc(a.time || "?") + ' &nbsp;·&nbsp; <b>Space:</b> ' + esc(a.space || "?") + "</p>";
        body += '<div class="dsa-code"><pre><code class="language-java">' + esc(a.code || "") + "</code></pre></div>";
        return '<div class="dsa-approach' + (i === 0 ? " active" : "") + '" data-panel="' + i + '">' + body + "</div>";
      }).join("");
    }
    if (entry.insight) h += '<div class="dsa-insight">💡 ' + inlineMd(entry.insight) + "</div>";
    bodyEl.innerHTML = h;
    highlight(bodyEl);
  }

  // ---------- highlight.js (lazy CDN load, theme-aware) ----------
  var HLJS_READY = null;
  function ensureHljs() {
    if (HLJS_READY) return HLJS_READY;
    var theme = document.createElement("link");
    theme.rel = "stylesheet"; theme.id = "dsa-hljs-theme";
    theme.href = hljsThemeHref();
    document.head.appendChild(theme);
    HLJS_READY = new Promise(function (res) {
      var s = document.createElement("script");
      s.src = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js";
      s.onload = function () { res(window.hljs); };
      s.onerror = function () { res(null); };
      document.head.appendChild(s);
    });
    observeScheme();
    return HLJS_READY;
  }
  function hljsThemeHref() {
    var dark = document.body.getAttribute("data-md-color-scheme") === "slate";
    return "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github" + (dark ? "-dark" : "") + ".min.css";
  }
  function observeScheme() {
    new MutationObserver(function () {
      var l = document.getElementById("dsa-hljs-theme"); if (l) l.href = hljsThemeHref();
    }).observe(document.body, { attributes: true, attributeFilter: ["data-md-color-scheme"] });
  }
  function highlight(scope) {
    ensureHljs().then(function (hljs) {
      if (!hljs) return;
      scope.querySelectorAll("pre code").forEach(function (b) { hljs.highlightElement(b); });
    });
  }

  // ---------- events ----------
  function onClick(e) {
    var t = e.target;
    // segmented view control
    var seg = t.closest("[data-view]");
    if (seg) {
      state.view = seg.getAttribute("data-view");
      localStorage.setItem(LS_VIEW, state.view);
      renderToolbarState();
      renderTopics();
      return;
    }
    // toggle topic
    var th = t.closest("[data-toggle]");
    if (th) {
      var slugv = th.getAttribute("data-toggle");
      var sec = th.parentElement;
      sec.classList.toggle("open");
      if (sec.classList.contains("open")) state.openTopics.add(slugv); else state.openTopics.delete(slugv);
      saveSet(LS_OPEN, state.openTopics);
      return;
    }
    // approach tabs
    var tab = t.closest(".dsa-tab");
    if (tab) {
      var wrap = tab.closest(".dsa-card-body");
      var idx = tab.getAttribute("data-tab");
      wrap.querySelectorAll(".dsa-tab").forEach(function (x) { x.classList.toggle("active", x === tab); });
      wrap.querySelectorAll(".dsa-approach").forEach(function (p) { p.classList.toggle("active", p.getAttribute("data-panel") === idx); });
      return;
    }
    // expand card
    var head = t.closest(".dsa-card-head");
    if (head) {
      var card = head.parentElement, id = card.getAttribute("data-id");
      var opening = !card.classList.contains("open");
      card.classList.toggle("open");
      if (opening && !card.dataset.loaded) {
        var m = state.byId[id];
        loadTopic(m.topicSlug).then(function (bucket) {
          renderDetail(card.querySelector(".dsa-card-body"), bucket[id]);
          card.dataset.loaded = "1";
        });
      }
      return;
    }
  }

  function bindToolbar() {
    document.getElementById("dsa-search").addEventListener("input", debounce(function (e) { state.search = e.target.value.trim().toLowerCase(); renderTopics(); }, 180));
    document.getElementById("dsa-diff").addEventListener("change", function (e) { state.difficulty = e.target.value; renderTopics(); });
    document.getElementById("dsa-sync").addEventListener("click", function () { syncLive(true); });
    state.root.addEventListener("click", onClick);
  }
  function debounce(fn, ms) { var h; return function () { var a = arguments, c = this; clearTimeout(h); h = setTimeout(function () { fn.apply(c, a); }, ms); }; }

  // ---------- boot ----------
  function boot() {
    var root = document.getElementById("dsa-app");
    if (!root) return;
    state.root = root;
    root.innerHTML =
      '<div class="dsa-toolbar">' +
      '<input id="dsa-search" type="search" placeholder="Search problems…" />' +
      '<select id="dsa-diff"><option value="all">All difficulty</option><option>Medium</option><option>Hard</option><option>Very Hard</option></select>' +
      '<div class="dsa-seg" role="tablist">' +
        '<button data-view="solved">Solved</button>' +
        '<button data-view="unsolved">Unsolved</button>' +
        '<button data-view="all">All</button>' +
      "</div>" +
      '<button class="dsa-btn primary" id="dsa-sync"><span id="dsa-sync-icon">⟳</span> Sync</button>' +
      '<span class="dsa-sync-time" id="dsa-sync-time"></span>' +
      "</div>" +
      '<div class="dsa-dash"></div>' +
      '<div class="dsa-topics"><div class="dsa-empty">Loading problems…</div></div>';

    // seed best-known solved: fallback snapshot, then cached live
    var cached = null; try { cached = JSON.parse(localStorage.getItem(LS_LIVE) || "null"); } catch (e) {}
    Promise.all([
      fetchJSON(DATA + "index.json"),
      fetchJSON(DATA + "solved.json").catch(function () { return {}; }),
      fetchJSON(DATA + "leetcode.json").catch(function () { return {}; }),
    ]).then(function (r) {
      state.index = r[0];
      state.byId = {};
      state.index.forEach(function (m) { state.byId[m.id] = m; });
      state.solved = cached && Object.keys(cached).length ? cached : r[1];
      state.lc = r[2] || {};
      bindToolbar();
      renderAll();
      syncLive(false); // refresh from live sheet in background if configured
    }).catch(function (err) {
      root.querySelector(".dsa-topics").innerHTML = '<div class="dsa-empty">Failed to load tracker data: ' + esc(String(err)) + "</div>";
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
