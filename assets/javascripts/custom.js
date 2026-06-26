/* =====================================================
   Study Material – Gamified UI Logic
   ===================================================== */

(function () {
  'use strict';

  /* ---- Constants ---- */
  var LLD_TOTAL = 38;
  var HLD_TOTAL = 7;
  var CON_TOTAL = 19;
  var XP_PER_PAGE = 50;
  var STORAGE_KEY = 'gm_completed';

  /* ---- Helpers ---- */
  function getCompleted() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}; }
    catch(e) { return {}; }
  }
  function saveCompleted(obj) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(obj));
  }

  function getPageKey() {
    return location.pathname.replace(/\/$/, '') || '/';
  }

  function getCategoryFromPath(path) {
    if (path.indexOf('/lld-design-prompts/') !== -1) return 'lld';
    if (path.indexOf('/hld-design-prompts/') !== -1) return 'hld';
    if (path.indexOf('/concept-prompts/')   !== -1) return 'con';
    return null;
  }

  function countByCategory(completed, prefix) {
    return Object.keys(completed).filter(function(k){ return k.indexOf(prefix) !== -1 && completed[k]; }).length;
  }

  function totalXP(completed) {
    return Object.values(completed).filter(Boolean).length * XP_PER_PAGE;
  }

  function showToast(msg) {
    var t = document.getElementById('gm-toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    setTimeout(function(){ t.classList.remove('show'); }, 2800);
  }

  /* ---- Inject static DOM elements ---- */
  function injectDOM() {
    // Reading progress bar
    var bar = document.createElement('div');
    bar.id = 'gm-progress-bar';
    document.body.insertBefore(bar, document.body.firstChild);

    // Toast
    var toast = document.createElement('div');
    toast.id = 'gm-toast';
    document.body.appendChild(toast);

    // Back-to-top button
    var topBtn = document.createElement('button');
    topBtn.id = 'gm-top-btn';
    topBtn.title = 'Back to top';
    topBtn.innerHTML = '&#8679;';
    topBtn.addEventListener('click', function(){
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
    document.body.appendChild(topBtn);

    // XP badge in header
    var nav = document.querySelector('.md-header__inner');
    if (nav) {
      var badge = document.createElement('div');
      badge.id = 'gm-xp-badge';
      badge.title = 'Your progress – click to view stats';
      badge.innerHTML = '<span class="gm-xp-icon">&#9889;</span><span id="gm-xp-val">0 XP</span>';
      badge.addEventListener('click', toggleStatsPanel);

      var source = nav.querySelector('.md-header__source');
      if (source) nav.insertBefore(badge, source);
      else nav.appendChild(badge);
    }

    // Stats panel
    var panel = document.createElement('div');
    panel.id = 'gm-stats-panel';
    panel.innerHTML = [
      '<h4>&#127942; Progress Dashboard</h4>',
      '<div class="gm-stat-row">',
        '<div class="gm-stat-label"><span>LLD Problems</span><span id="gm-lld-text">0/'+LLD_TOTAL+'</span></div>',
        '<div class="gm-stat-bar"><div class="gm-stat-bar-fill lld" id="gm-lld-bar" style="width:0%"></div></div>',
      '</div>',
      '<div class="gm-stat-row">',
        '<div class="gm-stat-label"><span>System Design (HLD)</span><span id="gm-hld-text">0/'+HLD_TOTAL+'</span></div>',
        '<div class="gm-stat-bar"><div class="gm-stat-bar-fill hld" id="gm-hld-bar" style="width:0%"></div></div>',
      '</div>',
      '<div class="gm-stat-row">',
        '<div class="gm-stat-label"><span>Concepts</span><span id="gm-con-text">0/'+CON_TOTAL+'</span></div>',
        '<div class="gm-stat-bar"><div class="gm-stat-bar-fill con" id="gm-con-bar" style="width:0%"></div></div>',
      '</div>',
      '<div class="gm-total-xp" id="gm-total-xp">0 XP</div>',
      '<div style="text-align:center;font-size:0.72rem;opacity:0.45;margin-top:6px">Click a topic and mark it complete to earn XP</div>'
    ].join('');
    document.body.appendChild(panel);

    // Close panel on outside click
    document.addEventListener('click', function(e){
      var p = document.getElementById('gm-stats-panel');
      var b = document.getElementById('gm-xp-badge');
      if (p && b && !p.contains(e.target) && !b.contains(e.target)) {
        p.classList.remove('open');
      }
    });
  }

  function toggleStatsPanel() {
    var p = document.getElementById('gm-stats-panel');
    if (p) p.classList.toggle('open');
  }

  /* ---- Update XP badge + stats panel ---- */
  function refreshStats() {
    var c = getCompleted();
    var xp = totalXP(c);
    var lld = countByCategory(c, '/lld-design-prompts/');
    var hld = countByCategory(c, '/hld-design-prompts/');
    var con = countByCategory(c, '/concept-prompts/');

    var xpVal = document.getElementById('gm-xp-val');
    if (xpVal) xpVal.textContent = xp + ' XP';

    var totalXpEl = document.getElementById('gm-total-xp');
    if (totalXpEl) totalXpEl.textContent = xp + ' XP';

    var lldText = document.getElementById('gm-lld-text');
    var lldBar  = document.getElementById('gm-lld-bar');
    if (lldText) lldText.textContent = lld + '/' + LLD_TOTAL;
    if (lldBar)  lldBar.style.width  = Math.round(lld/LLD_TOTAL*100) + '%';

    var hldText = document.getElementById('gm-hld-text');
    var hldBar  = document.getElementById('gm-hld-bar');
    if (hldText) hldText.textContent = hld + '/' + HLD_TOTAL;
    if (hldBar)  hldBar.style.width  = Math.round(hld/HLD_TOTAL*100) + '%';

    var conText = document.getElementById('gm-con-text');
    var conBar  = document.getElementById('gm-con-bar');
    if (conText) conText.textContent = con + '/' + CON_TOTAL;
    if (conBar)  conBar.style.width  = Math.round(con/CON_TOTAL*100) + '%';
  }

  /* ---- Mark Complete Button ---- */
  function injectCompleteButton() {
    var cat = getCategoryFromPath(location.pathname);
    if (!cat) return;

    var article = document.querySelector('.md-content__inner, article.md-content__inner, .md-typeset');
    if (!article) return;

    var key = getPageKey();
    var c = getCompleted();
    var done = !!c[key];

    var btn = document.createElement('button');
    btn.id = 'gm-complete-btn';
    btn.className = done ? 'done' : '';
    btn.innerHTML = done
      ? '<span class="gm-btn-icon">&#10003;</span> Completed'
      : '<span class="gm-btn-icon">&#9675;</span> Mark as Complete';

    btn.addEventListener('click', function(){
      var c2 = getCompleted();
      var wasDone = !!c2[key];
      c2[key] = !wasDone;
      saveCompleted(c2);

      if (!wasDone) {
        btn.innerHTML = '<span class="gm-btn-icon">&#10003;</span> Completed';
        btn.classList.add('done');
        showToast('&#9889; +' + XP_PER_PAGE + ' XP earned! Keep going!');
        markNavItemDone(key, true);
      } else {
        btn.innerHTML = '<span class="gm-btn-icon">&#9675;</span> Mark as Complete';
        btn.classList.remove('done');
        markNavItemDone(key, false);
      }
      refreshStats();
    });

    article.insertBefore(btn, article.firstChild);
  }

  /* ---- Mark nav sidebar items as done ---- */
  function markNavItemDone(key, done) {
    document.querySelectorAll('.md-nav__link').forEach(function(a){
      var href = a.getAttribute('href');
      if (href && key.endsWith(href.replace(/\/$/, ''))) {
        a.dataset.gmDone = done ? 'true' : 'false';
      }
    });
  }

  function applyDoneMarks() {
    var c = getCompleted();
    document.querySelectorAll('.md-nav__link').forEach(function(a){
      var href = a.getAttribute('href');
      if (!href) return;
      // Build the full key equivalent
      var base = location.pathname.replace(/[^/]+$/, '');
      var full = new URL(href, location.href).pathname.replace(/\/$/, '');
      if (c[full]) a.dataset.gmDone = 'true';
    });
  }

  /* ---- Reading Progress Bar ---- */
  function setupProgressBar() {
    var bar = document.getElementById('gm-progress-bar');
    if (!bar) return;

    function update() {
      var scrollTop = window.scrollY || document.documentElement.scrollTop;
      var docH = document.documentElement.scrollHeight - window.innerHeight;
      var pct = docH > 0 ? Math.round((scrollTop / docH) * 100) : 0;
      bar.style.width = pct + '%';

      var topBtn = document.getElementById('gm-top-btn');
      if (topBtn) {
        if (scrollTop > 300) topBtn.classList.add('visible');
        else topBtn.classList.remove('visible');
      }
    }
    window.addEventListener('scroll', update, { passive: true });
    update();
  }

  /* ---- Scroll Spy for TOC ---- */
  function setupScrollSpy() {
    var tocLinks = document.querySelectorAll('.md-nav[data-md-component="toc"] .md-nav__link');
    if (!tocLinks.length) return;

    var headings = [];
    tocLinks.forEach(function(a){
      var id = (a.getAttribute('href') || '').replace('#','');
      var el = id ? document.getElementById(id) : null;
      if (el) headings.push({ el: el, a: a });
    });

    if (!headings.length) return;

    var active = null;

    function onScroll() {
      var scrollY = window.scrollY + 120;
      var current = null;

      for (var i = headings.length - 1; i >= 0; i--) {
        if (headings[i].el.getBoundingClientRect().top + window.scrollY <= scrollY) {
          current = headings[i];
          break;
        }
      }

      if (current !== active) {
        if (active) active.a.classList.remove('gm-active');
        if (current) {
          current.a.classList.add('gm-active');
          // Scroll TOC into view
          scrollTOCToActive(current.a);
        }
        active = current;
      }
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  function scrollTOCToActive(link) {
    var scrollWrap = document.querySelector('.md-sidebar--secondary .md-sidebar__scrollwrap');
    if (!scrollWrap) return;

    // Walk offsetParent chain to get link's position within the scrollwrap content.
    // getBoundingClientRect is unreliable here because the scrollwrap is
    // position:absolute and clipped elements report out-of-viewport coords.
    var offsetTop = 0;
    var el = link;
    while (el && el !== scrollWrap) {
      offsetTop += el.offsetTop;
      el = el.offsetParent;
    }
    if (!el) return; // link is not a descendant of scrollWrap

    var wrapH     = scrollWrap.clientHeight;
    var scrollTop = scrollWrap.scrollTop;

    if (offsetTop < scrollTop + 40 || offsetTop > scrollTop + wrapH - 40) {
      scrollWrap.scrollTo({
        top: Math.max(0, offsetTop - Math.round(wrapH / 2)),
        behavior: 'smooth'
      });
    }
  }

  /* ---- Subtopic Quick-nav Chips ---- */
  function injectSubtopicChips() {
    var tocLinks = document.querySelectorAll('.md-nav[data-md-component="toc"] .md-nav__link');
    if (tocLinks.length < 3) return;

    var article = document.querySelector('.md-content__inner, .md-typeset');
    if (!article) return;

    var nav = document.createElement('div');
    nav.className = 'gm-subtopic-nav';

    tocLinks.forEach(function(a){
      var chip = document.createElement('a');
      chip.className = 'gm-subtopic-chip';
      chip.href = a.getAttribute('href') || '#';
      chip.textContent = (a.querySelector('.md-ellipsis') || a).textContent.trim();
      chip.addEventListener('click', function(e){
        e.preventDefault();
        document.querySelectorAll('.gm-subtopic-chip').forEach(function(c){ c.classList.remove('active'); });
        chip.classList.add('active');
        var id = chip.getAttribute('href').replace('#','');
        var target = document.getElementById(id);
        if (target) {
          var top = target.getBoundingClientRect().top + window.scrollY - 80;
          window.scrollTo({ top: top, behavior: 'smooth' });
        }
      });
      nav.appendChild(chip);
    });

    // Insert after the complete button or at the start of article
    var completeBtn = document.getElementById('gm-complete-btn');
    if (completeBtn && completeBtn.nextSibling) {
      article.insertBefore(nav, completeBtn.nextSibling);
    } else {
      article.insertBefore(nav, article.firstChild);
    }
  }

  /* ---- Homepage Category Cards ---- */
  function injectHomepageCards() {
    var isHome = location.pathname.replace(/\/$/, '') === (window.__md_scope ? window.__md_scope.pathname.replace(/\/$/, '') : '');
    if (!isHome && location.pathname !== '/' && !location.pathname.match(/\/Study-Material\/?$/)) return;

    // Look for the main content section
    var content = document.querySelector('.md-content__inner, .md-typeset');
    if (!content) return;

    // Check for the topics heading
    var topicsHeading = null;
    content.querySelectorAll('h2').forEach(function(h){
      if (h.textContent.trim().toLowerCase() === 'topics') topicsHeading = h;
    });
    if (!topicsHeading) return;

    var c = getCompleted();
    var lldDone = countByCategory(c, '/lld-design-prompts/');
    var hldDone = countByCategory(c, '/hld-design-prompts/');
    var conDone = countByCategory(c, '/concept-prompts/');

    var cards = document.createElement('div');
    cards.className = 'gm-home-cards';

    var defs = [
      {
        cls: 'lld',
        icon: '&#128187;',
        title: 'LLD Problems',
        desc: 'Low-level design patterns, class diagrams, SOLID principles, and interview-ready solutions.',
        count: LLD_TOTAL + ' problems',
        done: lldDone,
        total: LLD_TOTAL,
        href: 'lld-design-prompts/01-parking-lot/01-parking-lot/'
      },
      {
        cls: 'hld',
        icon: '&#128640;',
        title: 'System Design (HLD)',
        desc: 'High-level architecture, scalability patterns, distributed systems, and real-world designs.',
        count: HLD_TOTAL + ' modules',
        done: hldDone,
        total: HLD_TOTAL,
        href: 'hld-design-prompts/01-fundamentals-and-framework/01-the-design-framework/01-the-design-framework/'
      },
      {
        cls: 'con',
        icon: '&#128214;',
        title: 'Concepts',
        desc: 'Deep dives into distributed systems, databases, caching, Kafka, Kubernetes, security, and AI.',
        count: CON_TOTAL + ' topics',
        done: conDone,
        total: CON_TOTAL,
        href: 'concept-prompts/01-distributed-systems-foundations/01-cap-and-pacelc/01-cap-and-pacelc/'
      }
    ];

    defs.forEach(function(d){
      var pct = Math.round(d.done / d.total * 100);
      var card = document.createElement('a');
      card.className = 'gm-card ' + d.cls;
      card.href = d.href;
      card.innerHTML = [
        '<span class="gm-card-icon">' + d.icon + '</span>',
        '<div class="gm-card-title">' + d.title + '</div>',
        '<div class="gm-card-desc">' + d.desc + '</div>',
        '<div class="gm-card-count">' + d.count + ' &bull; ' + d.done + '/' + d.total + ' done</div>',
        '<div class="gm-card-progress"><div class="gm-card-progress-fill" style="width:' + pct + '%"></div></div>'
      ].join('');
      cards.appendChild(card);
    });

    topicsHeading.parentNode.insertBefore(cards, topicsHeading.nextSibling);
  }

  /* ---- Highlight active nav sidebar link ---- */
  function highlightActiveSidebarLink() {
    var currentPath = location.pathname.replace(/\/$/, '');
    document.querySelectorAll('.md-nav--primary .md-nav__link').forEach(function(a){
      var href = a.getAttribute('href');
      if (!href) return;
      var resolved = new URL(href, location.href).pathname.replace(/\/$/, '');
      if (resolved === currentPath) {
        a.classList.add('gm-active');
        // Expand parent
        var parentNav = a.closest('nav.md-nav');
        if (parentNav) {
          var toggle = document.querySelector('input[aria-controls="' + parentNav.id + '"]');
          if (toggle) toggle.checked = true;
        }
      }
    });
  }

  /* ---- Init ---- */
  function init() {
    injectDOM();
    applyDoneMarks();
    refreshStats();
    injectCompleteButton();
    setupProgressBar();
    setupScrollSpy();
    injectSubtopicChips();
    injectHomepageCards();
    highlightActiveSidebarLink();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  // MkDocs uses instant navigation — re-run on page change
  document.addEventListener('DOMContentSwitch', function(){
    setTimeout(function(){
      applyDoneMarks();
      refreshStats();
      injectCompleteButton();
      setupProgressBar();
      setupScrollSpy();
      injectSubtopicChips();
      injectHomepageCards();
      highlightActiveSidebarLink();
    }, 100);
  });

})();
