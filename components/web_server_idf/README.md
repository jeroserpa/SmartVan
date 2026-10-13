# web_server_idf — ESPHome 2026.8.0, with one fix (D-29)

A copy of ESPHome 2026.8.0's `esphome/components/web_server_idf`, listed in
`nodes/van-core.yaml`'s `external_components` so it replaces the built-in one
of the same name. Everything here is upstream's code, byte for byte, except
one block in `web_server_idf.cpp`, `AsyncEventSourceResponse::process_buffer_()`,
marked `van-supervisor patch`.

**What it fixes.** When a live stream (`/events`) has not drained for ~20 s,
upstream gives up on it by marking it dead, and the main loop then frees it —
without ever closing the socket. httpd keeps that socket open for as long as
the client keeps its end, holding one of van-core's 10 sockets, and when httpd
does eventually close it, its `free_ctx` callback writes into the freed
object. On van-core that is how a paused phone app, a backgrounded browser tab
or a weak link takes a socket away for hours — and the 2026-10-13 outage
(`docs/measurements.md` M20) was the node running out of them. The patch asks
httpd to close the session instead, which runs the normal teardown.

**When to delete this directory.** Upstream fixed it in 2026.10
(`request_close_()` / `process_close_()`, which also guards against closing a
reused fd). ESPHome is held below 2026.9 by `sd_file_server` (see
`nodes/van-core.yaml`); once the project moves past that, remove this copy and
its entry in `external_components`.

**Licence.** ESPHome's: the C++ files are GPLv3, the Python is MIT — see
`LICENSE`, copied from the 2026.8.0 distribution.
