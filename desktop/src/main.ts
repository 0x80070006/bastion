// SPDX-License-Identifier: GPL-3.0-or-later
import { mount } from "svelte";
import App from "./App.svelte";
import { PRODUCT_NAME } from "./lib/brand";
import "./lib/theme/base.css";

document.title = PRODUCT_NAME;

const target = document.getElementById("app");
if (!target) throw new Error("#app mount point missing");

export default mount(App, { target });
