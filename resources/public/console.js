/* Live email preview for the Operator console (ADR-0020).
 *
 * While the operator types, this file posts the composer form to /preview
 * and shows the result. The server builds the preview with the same
 * function that builds the sent email, so the preview is the email.
 *
 *   #composer       the form (kind, subject, body, anti-forgery token)
 *   #preview-html   sandboxed iframe for the HTML part
 *   #preview-text   <pre> for the plain-text part
 *   #test-status    whether the current draft is the tested draft
 *   [data-preview]  the HTML / Plain text toggle buttons
 */

(function () {
  const form = document.getElementById("composer");
  if (!form) return;

  const frame = document.getElementById("preview-html");
  const plain = document.getElementById("preview-text");
  const status = document.getElementById("test-status");
  let timer;
  let latest = 0;

  function showStatus(text, className) {
    status.textContent = text;
    status.className = className;
  }

  // Requests can finish out of order, so only the newest one updates the page.
  async function refresh() {
    const request = ++latest;
    try {
      const response = await fetch("/preview", {
        method: "POST",
        body: new URLSearchParams(new FormData(form)),
      });
      if (request !== latest) return;
      if (!response.ok) throw new Error(String(response.status));
      const data = await response.json();
      if (request !== latest) return;
      frame.srcdoc = data.html;
      plain.textContent = data.text;
      showStatus(data.status, data.tested ? "text-sm text-success" : "text-sm opacity-70");
    } catch (error) {
      // After a restart the session is gone and the anti-forgery token
      // is stale, so every preview fails until the page is reloaded.
      if (request === latest) {
        showStatus("The preview stopped. Copy your text, then reload the page.",
                   "text-sm text-error");
      }
    }
  }

  form.addEventListener("input", function () {
    clearTimeout(timer);
    timer = setTimeout(refresh, 300);
  });

  form.addEventListener("submit", function () {
    clearTimeout(timer);
    latest++;
  });

  const toggles = document.querySelectorAll("[data-preview]");
  toggles.forEach(function (button) {
    button.addEventListener("click", function () {
      const html = button.dataset.preview === "html";
      frame.hidden = !html;
      plain.hidden = html;
      toggles.forEach(function (other) {
        other.setAttribute("aria-pressed", String(other === button));
        other.classList.toggle("btn-active", other === button);
      });
    });
  });
})();
