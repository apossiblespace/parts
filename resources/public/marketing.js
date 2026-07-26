/* Analytics wiring for the public pages (marketing, legal, playground).
 *
 * Exists so those pages can carry a strict Content-Security-Policy with no
 * 'unsafe-inline': elements declare what to track via data attributes and
 * this file wires the listeners.
 *
 *   data-analytics         event name sent to plausible()
 *   data-analytics-source  becomes {props: {source: ...}}
 *   data-analytics-on      "click" (default) | "focus" | "submit"
 *
 * A waitlist-success fragment swapped in by htmx may carry
 * data-counter-increment to bump the visible #counter once.
 */

window.plausible = window.plausible || function () {
  (window.plausible.q = window.plausible.q || []).push(arguments);
};

(function () {
  function fire(el) {
    var source = el.getAttribute('data-analytics-source');
    window.plausible(el.getAttribute('data-analytics'),
                     source ? { props: { source: source } } : undefined);
  }

  function on(kind) {
    return function (e) {
      var el = e.target.closest && e.target.closest('[data-analytics]');
      if (el && (el.getAttribute('data-analytics-on') || 'click') === kind) {
        fire(el);
      }
    };
  }

  document.addEventListener('click', on('click'));
  document.addEventListener('focusin', on('focus'));
  document.addEventListener('submit', on('submit'), true);

  document.addEventListener('htmx:afterSwap', function () {
    var el = document.querySelector('[data-counter-increment]:not([data-counted])');
    var counter = document.getElementById('counter');
    if (el && counter) {
      el.setAttribute('data-counted', '');
      counter.textContent = (parseInt(counter.textContent, 10) || 0) + 1;
    }
  });
})();
