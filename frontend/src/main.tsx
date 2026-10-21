import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { bootstrap } from "./api/client";
import { captureResetToken } from "./auth/AuthPages";
import "./styles.css";
captureResetToken();
await bootstrap();
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
