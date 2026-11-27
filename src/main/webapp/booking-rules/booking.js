const base = "../api/booking-rules/";
const result = document.querySelector("#result");

document.querySelectorAll("form[data-path]").forEach(form => {
    form.addEventListener("submit", async event => {
        event.preventDefault();
        const button = form.querySelector("button");
        button.disabled = true;
        try {
            const data = new URLSearchParams(new FormData(form));
            const path = base + form.dataset.path;
            let response;
            if (form.method === "post") {
                const token = await fetch(base + "token");
                if (!token.ok) {
                    result.textContent = await token.text();
                    return;
                }
                data.set("csrf", await token.text());
                response = await fetch(path, { method: "POST", body: data });
            } else {
                response = await fetch(path + "?" + data);
            }
            result.textContent = await response.text();
            result.focus();
        } catch {
            result.textContent = "请求失败，请检查服务是否启动。";
        } finally {
            button.disabled = false;
        }
    });
});
