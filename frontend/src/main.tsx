import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { bootstrap } from "./api/client";
import { captureResetToken } from "./auth/AuthPages";
import "./styles.css";
import "./theme.css";
import "./orange-juice.css";
import "./party-effects.css";
import "./party-controls.css";
captureResetToken();
await bootstrap();
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
