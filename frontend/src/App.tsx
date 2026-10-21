import { lazy, Suspense, useEffect } from "react";
import { BrowserRouter, Route, Routes, useLocation } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./api/cache";
import {
  Header,
  Navigation,
  NavigationProvider,
  Loading,
  Empty,
  Page,
} from "./shared/ui";
import { Protected } from "./auth/AuthPages";
import { PreferencesSync } from "./settings/Settings";
const Wall = lazy(() => import("./wall/Wall"));
const AuthPage = lazy(() => import("./auth/AuthPages"));
const Detail = lazy(() => import("./works/Detail"));
const Lists = lazy(() => import("./profile/Lists"));
const Publish = lazy(() => import("./publishing/Publish"));
const Settings = lazy(() => import("./settings/Settings"));
function Scroll() {
  const location = useLocation();
  useEffect(() => {
    if (location.pathname !== "/") window.scrollTo(0, 0);
    document.title = "Here the World · 世界在此";
  }, [location.pathname]);
  return null;
}
export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <NavigationProvider>
          <Scroll />
          <PreferencesSync />
          <a className="skip-link" href="#content">
            跳到内容
          </a>
          <Header />
          <Navigation />
          <div id="content">
            <Suspense fallback={<Loading />}>
              <Routes>
                <Route path="/" element={<Wall />} />
                <Route path="/works/:workId" element={<Detail />} />
                <Route path="/users/:userId" element={<Lists />} />
                <Route
                  path="/me/works"
                  element={
                    <Protected>
                      <Lists />
                    </Protected>
                  }
                />
                <Route
                  path="/me/bookmarks"
                  element={
                    <Protected>
                      <Lists />
                    </Protected>
                  }
                />
                <Route
                  path="/publish"
                  element={
                    <Protected>
                      <Publish />
                    </Protected>
                  }
                />
                <Route
                  path="/publish/:workId"
                  element={
                    <Protected>
                      <Publish />
                    </Protected>
                  }
                />
                <Route path="/settings" element={<Settings />} />
                {[
                  "login",
                  "register",
                  "verify-email",
                  "forgot-password",
                  "reset-password",
                ].map((path) => (
                  <Route
                    key={path}
                    path={`/${path}`}
                    element={<AuthPage key={path} />}
                  />
                ))}
                <Route
                  path="*"
                  element={
                    <Page eyebrow="OFF THE MAP" title="走到了地图之外。">
                      <Empty>这里还没有留下足迹。</Empty>
                    </Page>
                  }
                />
              </Routes>
            </Suspense>
          </div>
        </NavigationProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
