const avatar = (name: string) => `/media/orange-juice/avatars/${name}.jpg`;

export const orangeJuiceCharacters = {
  qp: {
    name: "QP",
    src: "/media/orange-juice/qp.png",
    portrait: avatar("qp"),
    edge: "#ce7d96",
    paper: "#f7d6d5",
  },
  suguri: {
    name: "Suguri",
    src: "/media/orange-juice/suguri.png",
    portrait: avatar("suguri"),
    edge: "#6c9db8",
    paper: "#d8e9ed",
  },
  marc: {
    name: "Marc",
    src: "/media/orange-juice/marc.png",
    portrait: avatar("marc"),
    edge: "#c98c58",
    paper: "#f5dfc4",
  },
  kai: {
    name: "Kai & Marie Poppo",
    src: "/media/orange-juice/kai-poppo.png",
    portrait: avatar("kai"),
    edge: "#9a895b",
    paper: "#eee3c2",
  },
  poppo: {
    name: "Marie Poppo",
    src: avatar("poppo"),
    portrait: avatar("poppo"),
    edge: "#ce866c",
    paper: "#f7dac9",
  },
  sora: {
    name: "Sora",
    src: avatar("sora"),
    portrait: avatar("sora"),
    edge: "#91a55b",
    paper: "#e6ebc3",
  },
  hime: {
    name: "Hime",
    src: avatar("hime"),
    portrait: avatar("hime"),
    edge: "#bd8baf",
    paper: "#f3dce9",
  },
  fernet: {
    name: "Fernet",
    src: avatar("fernet"),
    portrait: avatar("fernet"),
    edge: "#7eaa9e",
    paper: "#d7eae0",
  },
  peat: {
    name: "Peat",
    src: avatar("peat"),
    portrait: avatar("peat"),
    edge: "#8e9ec3",
    paper: "#e0e7f4",
  },
  aru: {
    name: "Aru",
    src: avatar("aru"),
    portrait: avatar("aru"),
    edge: "#cb859d",
    paper: "#f6dbe5",
  },
  yuki: {
    name: "Yuki",
    src: avatar("yuki"),
    portrait: avatar("yuki"),
    edge: "#a390be",
    paper: "#e8def1",
  },
  tomomo: {
    name: "Tomomo",
    src: avatar("tomomo"),
    portrait: avatar("tomomo"),
    edge: "#b4916a",
    paper: "#f0e2ca",
  },
} as const;

export type OrangeJuiceCharacter = keyof typeof orangeJuiceCharacters;
export const characterRoster = Object.keys(
  orangeJuiceCharacters,
) as OrangeJuiceCharacter[];

/** Only decorative selection: never an account preference or an API field. */
export function characterForPath(path: string): OrangeJuiceCharacter {
  if (path.startsWith("/settings")) return "suguri";
  if (path.startsWith("/publish")) return "aru";
  if (path.startsWith("/me/bookmarks")) return "hime";
  if (path.startsWith("/me/works")) return "marc";
  if (path.startsWith("/users/")) return "kai";
  if (path.startsWith("/register")) return "sora";
  if (path.startsWith("/verify-email")) return "fernet";
  if (path.includes("password")) return "yuki";
  if (path.startsWith("/works/")) {
    let seed = 0;
    for (const letter of path)
      seed = (Math.imul(seed, 31) + letter.charCodeAt(0)) >>> 0;
    return characterRoster[seed % characterRoster.length];
  }
  return "qp";
}
