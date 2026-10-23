/* Reuse existing back controls so forms and nested pages keep their own rules. */
(function () {
  const selector = 'button[aria-label^="返回"], button[id$="-back"], button[id$="-close"]';
  const ignore = 'input, textarea, select, button, a, [contenteditable="true"], [role="slider"], [data-no-swipe-back], canvas, audio, video';
  let start = null;
  let lastBack = 0;

  function backControl() {
    const candidates = Array.from(document.querySelectorAll(selector)).filter((button) => {
      // Never turn a gesture into hanging up a call or changing a lock state.
      if (/call|lock|auth/.test(button.id) || button.disabled || button.closest('[hidden], [aria-hidden="true"]')) return false;
      const rect = button.getBoundingClientRect();
      if (!rect.width || !rect.height) return false;
      const x = Math.max(0, Math.min(innerWidth - 1, rect.left + rect.width / 2));
      const y = Math.max(0, Math.min(innerHeight - 1, rect.top + rect.height / 2));
      if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= innerWidth || rect.top >= innerHeight) return false;
      return button.contains(document.elementFromPoint(x, y));
    });
    // Visible controls on a nested pane occur after their parent in this UI.
    return candidates[candidates.length - 1] || null;
  }

  function goBack(control = backControl()) {
    if (!control || performance.now() - lastBack < 400) return false;
    lastBack = performance.now();
    control.click();
    return true;
  }

  document.addEventListener("touchstart", (event) => {
    start = null;
    if (event.touches.length !== 1 || event.target.closest(ignore)) return;
    if (window.getSelection()?.toString()) return;
    const touch = event.touches[0];
    const control = backControl();
    if (!control) return;
    // Keep carousels and other horizontally scrollable controls usable.
    for (let node = event.target; node && node !== document.body; node = node.parentElement) {
      if (node.scrollWidth > node.clientWidth + 8 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return;
    }
    start = { x: touch.clientX, y: touch.clientY, time: performance.now(), control, horizontal: false };
  }, { passive: true });

  document.addEventListener("touchmove", (event) => {
    if (!start) return;
    if (event.touches.length !== 1) { start = null; return; }
    const touch = event.touches[0];
    const dx = Math.abs(touch.clientX - start.x);
    const dy = Math.abs(touch.clientY - start.y);
    if (!start.horizontal && dy > 12 && dy > dx) { start = null; return; }
    if (dx > 18 && dx > dy * 1.6) start.horizontal = true;
    if (start.horizontal && event.cancelable) event.preventDefault();
  }, { passive: false });

  document.addEventListener("touchend", (event) => {
    const gesture = start;
    start = null;
    if (!gesture || !event.changedTouches.length) return;
    const touch = event.changedTouches[0];
    const dx = Math.abs(touch.clientX - gesture.x);
    const dy = Math.abs(touch.clientY - gesture.y);
    if (dx >= 72 && dx > dy * 1.8 && performance.now() - gesture.time < 1200 && backControl() === gesture.control) {
      goBack(gesture.control);
    }
  }, { passive: true });
  document.addEventListener("touchcancel", () => { start = null; }, { passive: true });

  // In an installed PWA, each Android browser-back event returns one app level.
  // Re-arm synchronously on every event, including when already at the desktop.
  if (matchMedia("(display-mode: standalone)").matches || navigator.standalone === true) {
    const guard = { ...(history.state || {}), meixinjiBackGuard: true };
    if (!history.state?.meixinjiBackGuard) history.pushState(guard, "", location.href);
    window.addEventListener("popstate", () => {
      history.pushState(guard, "", location.href);
      goBack();
    });
  }
})();
