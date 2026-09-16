"""Inspect llama.cpp's raw string carrier, independently of its tool-call parser.

This development probe generates text only. It never executes returned bodies.
Defaults target the served Gemma template; delimiters can be specified explicitly
for another template. Exit status reports exact-body checks, not model reliability.
"""
import argparse
import json
import urllib.request


def post(base, path, data, timeout, limit):
    request = urllib.request.Request(
        base.rstrip("/") + path,
        data=json.dumps(data).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read(limit + 1)
    if len(body) > limit:
        raise ValueError("Probe response exceeds byte limit")
    return json.loads(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--open", default='<|"|>')
    parser.add_argument("--close", default='<|"|>')
    parser.add_argument("--stop", default="<tool_call|>")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--max-bytes", type=int, default=1048576)
    parser.add_argument("--crlf", action="store_true")
    parser.add_argument("--output", help="Optional JSON evidence file")
    args = parser.parse_args()
    if args.timeout <= 0 or args.max_bytes <= 0:
        parser.error("timeout and max-bytes must be positive")

    body = '  print("hello")\npath = "C:\\tmp\\file"\n'
    if args.crlf:
        body = body.replace("\n", "\r\n")
    arguments = {"id": "code", "lang": "python", "body": body}
    tool = {"type": "function", "function": {
        "name": "define_payload",
        "description": "Store the exact body as a native string parameter. Do not pre-encode it as JSON.",
        "parameters": {"type": "object", "properties": {
            field: {"type": "string"} for field in arguments
        }, "required": list(arguments)},
    }}
    messages = [{"role": "user", "content":
                 "Call define_payload once with id code, lang python, and the exact body "
                 "between BEGIN and END, excluding the markers. Preserve leading spaces, "
                 "all newline characters, and the trailing newline.\nBEGIN\n" + body + "END"}]
    request = {"messages": messages, "tools": [tool],
               "chat_template_kwargs": {"enable_thinking": False}}
    send = lambda path, data: post(args.base_url, path, data, args.timeout, args.max_bytes)
    formatted = send("/apply-template", request)
    generated = send("/completion", {"prompt": formatted["prompt"], "temperature": 0,
                                      "n_predict": 512, "stop": [args.stop]})
    replay_request = dict(request, messages=messages + [
        {"role": "assistant", "content": "", "tool_calls": [
            {"id": "probe", "type": "function", "function": {
                "name": "define_payload", "arguments": json.dumps(arguments)}}]},
        {"role": "tool", "tool_call_id": "probe", "content": "Stored."},
        {"role": "user", "content": "Continue."},
    ])
    replay = send("/apply-template", replay_request)
    wrapped = args.open + body + args.close
    checks = {"raw_generation_preserved": wrapped in generated["content"],
              "replay_preserved": wrapped in replay["prompt"]}
    evidence = {"checks": checks, "expected_body": body, "request": request,
                "formatted": formatted, "generated": generated, "replay": replay}
    if args.output:
        with open(args.output, "w", encoding="utf-8") as output:
            json.dump(evidence, output, ensure_ascii=False, indent=2)
    print(json.dumps(checks))
    print("Raw generation:\n" + generated["content"])
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
