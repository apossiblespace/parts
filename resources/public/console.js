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
 *   [data-audience] the recipient counts of step 3, by kind
 *   #send-button    enabled only for the tested draft
 *
 * While an email sends, the banner carries data-sending. The script then
 * polls /sends/current every second and updates [data-progress] and
 * [data-bar]. When the send ends it reloads the page once, so the server
 * renders the final state.
 */

(function () {
  const live = document.querySelector("[data-sending]");
  if (!live) return;

  function fill(key, value) {
    document.querySelectorAll('[data-progress="' + key + '"]').forEach(function (el) {
      el.textContent = value;
    });
  }

  function width(key, n, total) {
    document.querySelectorAll('[data-bar="' + key + '"]').forEach(function (el) {
      el.style.width = (total > 0 ? (100 * n) / total : 0) + "%";
    });
  }

  async function poll() {
    try {
      const response = await fetch("/sends/current");
      if (!response.ok) return;
      const data = await response.json();
      if (data.state !== "sending" || data.id !== live.dataset.sending) {
        location.reload();
        return;
      }
      fill("done", data.sent + data.failed);
      fill("sent", data.sent);
      fill("failed", data.failed);
      fill("total", data.total);
      width("sent", data.sent, data.total);
      width("failed", data.failed, data.total);
    } catch (error) {
      // A missed poll is harmless. The next one tries again.
    }
  }

  setInterval(poll, 1000);
})();

(function () {
  const form = document.getElementById("composer");
  if (!form) return;

  const frame = document.getElementById("preview-html");
  const plain = document.getElementById("preview-text");
  const status = document.getElementById("test-status");
  const send = document.getElementById("send-button");
  const sending = document.querySelector("[data-sending]") !== null;
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
      send.disabled = sending || !data.tested;
      Object.entries(data.audience).forEach(function ([key, value]) {
        form.querySelectorAll('[data-audience="' + key + '"]').forEach(function (el) {
          el.textContent = value;
        });
      });
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
