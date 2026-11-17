export interface NavLink {
  label: string;
  href: string;
}

export interface SiteConfig {
  title: string;
  description: string;
  author: string;
  /** Sidebar author card. The about page does not use this field. */
  bio: string;
  /** Single character for the 404 seal and /favicon.svg. The site logo is public/logo.jpg. */
  seal: string;
  url: string;
  lang: string;
  postsPerPage: number;
  nav: NavLink[];
  social: NavLink[];
}

export const siteConfig: SiteConfig = {
  title: '我的博客',
  description: '记录技术与生活的个人博客。',
  author: 'lightwing',
  bio: '写代码，也写生活。这里记下踩过的坑、做过的取舍，和值得留住的片段。',
  seal: 'G',
  url: 'https://example.com',
  lang: 'zh-CN',
  postsPerPage: 10,
  nav: [
    { label: '文章', href: '/posts/' },
    { label: '归档', href: '/archive/' },
    { label: '标签', href: '/tags/' },
    { label: '关于', href: '/about/' },
  ],
  social: [
    { label: 'GitHub', href: 'https://github.com/cute-rui' },
    { label: 'Atom', href: '/atom.xml' },
  ],
};
