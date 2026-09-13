/*
 * The only JavaScript in the application. Everything works without it:
 *   - the lecturer picker falls back to its "Show" button
 *   - confirmations are a courtesy, not the security check; the server
 *     re-checks every permission on every request
 *   - copy buttons fall back to selecting the text so it can be copied by hand
 *
 * The Content-Security-Policy blocks inline scripts and onclick= attributes,
 * so every listener is attached here.
 */
(function () {
    'use strict';

    /* Submitting a picker as soon as it changes saves a click. */
    document.querySelectorAll('select[data-autosubmit]').forEach(function (select) {
        select.addEventListener('change', function () {
            if (select.form) {
                select.form.submit();
            }
        });
    });

    /* With JavaScript on, the no-JS fallback buttons are redundant. */
    document.querySelectorAll('[data-hide-when-js]').forEach(function (el) {
        el.hidden = true;
    });

    /* "Are you sure?" before anything destructive. */
    document.querySelectorAll('[data-confirm]').forEach(function (el) {
        el.addEventListener('click', function (event) {
            if (!window.confirm(el.getAttribute('data-confirm'))) {
                event.preventDefault();
            }
        });
    });

    /* Copy a link or key to the clipboard. */
    document.querySelectorAll('[data-copy-for]').forEach(function (button) {
        button.addEventListener('click', function () {
            var field = document.getElementById(button.getAttribute('data-copy-for'));
            if (!field) {
                return;
            }

            // Secrets are rendered in password fields so they are not readable
            // over a shoulder. Reveal briefly so the copy is verifiable.
            var wasPassword = field.type === 'password';
            if (wasPassword) {
                field.type = 'text';
            }
            field.select();
            field.setSelectionRange(0, field.value.length);

            var done = function (ok) {
                var original = button.textContent;
                button.textContent = ok ? 'Copied' : 'Press Ctrl+C';
                window.setTimeout(function () {
                    button.textContent = original;
                    if (wasPassword) {
                        field.type = 'password';
                    }
                }, 1600);
            };

            // navigator.clipboard is unavailable over plain http on some
            // browsers, so the selection above is the fallback either way.
            if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(field.value).then(function () {
                    done(true);
                }, function () {
                    done(false);
                });
            } else {
                done(false);
            }
        });
    });

    /*
     * Progress bars. The width comes from a data attribute rather than a style
     * attribute, because the Content-Security-Policy blocks inline styles.
     * Setting .style from script is not inline CSS and is allowed.
     */
    document.querySelectorAll('[data-meter-percent]').forEach(function (el) {
        var percent = parseFloat(el.getAttribute('data-meter-percent'));
        if (!isNaN(percent)) {
            el.style.width = Math.max(0, Math.min(100, percent)) + '%';
        }
    });
})();
