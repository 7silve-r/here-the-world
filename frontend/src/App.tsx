import { lazy, Suspense, useEffect } from "react";
import { BrowserRouter, Route, Routes, useLocation } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./api/cache";
import {
  NavigationToggle,
  Navigation,
  NavigationProvider,
  Loading,
  Empty,
  Page,
} from "./shared/ui";
import { Protected } from "./auth/AuthPages";
import { PreferencesSync } from "./settings/Settings";
import { MotionSystem, RouteScene } from "./shared/animation";
import { loadWorkPage, preparedWorkPage } from "./shared/cover-transition";
import { AmbientDust } from "./shared/atmosphere";
function PageLight() {
  return useLocation().pathname === "/" ? null : <AmbientDust backdrop />;
}
function NavigationChrome() {
  if (useLocation().pathname === "/welcome") return null;
  return (
    <>
      <SkipLink />
      <NavigationToggle />
      <Navigation />
    </>
  );
}
const Wall = lazy(() => import("./wall/Wall"));
const Welcome = lazy(() => import("./welcome/Welcome"));
const AuthPage = lazy(() => import("./auth/AuthPages"));
const Detail = lazy(loadWorkPage);
function WorkRoute() {
  const Component = preparedWorkPage() || Detail;
  return <Component />;
}
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
function SkipLink() {
  if (useLocation().pathname === "/") return null;
  return (
    <a className="skip-link" href="#content">
      跳到内容
    </a>
  );
}
export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <NavigationProvider>
          <MotionSystem>
            <Scroll />
            <PreferencesSync />
            <PageLight />
            <NavigationChrome />
            <div id="content">
              <RouteScene>
                <Suspense fallback={<Loading />}>
                  <Routes>
                    <Route path="/" element={<Wall />} />
                    <Route path="/welcome" element={<Welcome />} />
                    <Route path="/works/:workId" element={<WorkRoute />} />
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
                        <Page title="页面不存在">
                          <Empty>请检查页面地址</Empty>
                        </Page>
                      }
                    />
                  </Routes>
                </Suspense>
              </RouteScene>
            </div>
          </MotionSystem>
        </NavigationProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
